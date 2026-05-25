package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import com.grammacrackers.chatpilot.explore.StructureMarker;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Explore task. Replaces the old "Forage" feature with something with a real
 * goal: find a structure the bot has never been to before, walk over to it,
 * pick up loot, head home.
 *
 * Design goals (per Austin's stream notes):
 *   - No countdown timer is exposed to chat. The HUD shows "Exploring..."
 *     and that's it. The task ends when the structure has been reached and
 *     looted, or when the safety timeout (in TaskManager) trips.
 *   - The structure name is never revealed in the HUD or logs visible to
 *     viewers. Internal logs still name it for debugging.
 *   - Same kind in the same area never repeats. Visited markers are stored
 *     in {@code config/chatpilot/visited.json}.
 *   - Underwater structures are skipped because pathfinding through liquid
 *     gets the bot stuck. Even with water-breathing, we just don't want to
 *     watch a 5 minute swim on stream.
 *
 * Stages:
 *   SCAN_AND_HUNT - Look at all loaded chunks for any unvisited surface
 *                   marker block. If found, lock onto it. Otherwise issue
 *                   {@code explore} so Baritone walks outward and loads
 *                   fresh chunks; we re-scan every tick.
 *   APPROACH       - Path to within {@link #ARRIVAL_DIST} blocks of the
 *                    chosen marker.
 *   WANDER         - Wander around the structure for a while so loot
 *                    pickups and chest scans cover the whole site.
 *   FIND_CHEST     - Scan {@link #CHEST_RADIUS} blocks around current
 *                    position for a chest we haven't opened yet.
 *   APPROACH_CHEST - Path to the chest.
 *   OPEN_CHEST     - Right-click the chest.
 *   LOOT           - Quick-move every stack from chest to player.
 *   CLOSE_CHEST    - Close the screen handler.
 *   DONE           - Mark structure visited; let TaskManager start the
 *                    return-home chain.
 */
public class ExploreTask implements Task {

    /* ---------- timing knobs ---------- */
    private static final int  HUNT_TIMEOUT_SECONDS      = 360;  // 6 min hard ceiling on the search
    private static final int  RESCAN_INTERVAL_TICKS     = 60;   // scan every 3s (Baritone explore moves ~5b/s)
    private static final int  EXPLORE_REISSUE_TICKS     = 200;  // re-issue 'explore' every 10s
    private static final int  APPROACH_TIMEOUT_SECONDS  = 90;
    private static final int  CHEST_OPEN_TIMEOUT_TICKS  = 100;  // 5s
    private static final int  CHEST_LOOT_INTERVAL_TICKS = 2;
    private static final int  ARRIVAL_DIST              = 6;    // squared dist threshold = 36
    private static final int  CHEST_RADIUS              = 16;
    /** Keep the box small enough that one scan is well under 1ms even on a low-end CPU. */
    private static final int  MARKER_SCAN_HORIZ         = 64;
    private static final int  MARKER_SCAN_VERT          = 16;
    private static final int  WATER_AVOID_RADIUS        = 4;
    /** Markers within this many blocks (horizontally) of home are ignored, so
     *  the explore task heads OUT to find something new instead of locking onto
     *  the structure the house itself sits in/next to and walking back home. */
    private static final int  MARKER_MIN_DIST_FROM_HOME = 128;

    private enum Stage { SCAN_AND_HUNT, APPROACH, WANDER, FIND_CHEST, APPROACH_CHEST,
                         OPEN_CHEST, LOOT, CLOSE_CHEST, DONE }

    private final StructureMarker.Mode mode;
    private final String     displayLabel;

    private Stage    stage = Stage.SCAN_AND_HUNT;
    private int      stageStartTick;
    private int      taskStartTick;
    private int      lastScanTick;
    private int      lastExploreReissueTick;

    private StructureMarker.Type chosenType;
    private BlockPos             chosenMarker;
    private String               worldDimension = "minecraft:overworld";

    private final Deque<BlockPos> chestQueue = new ArrayDeque<>();
    private final Set<BlockPos>   chestsHandled = new LinkedHashSet<>();
    private BlockPos              currentChest;
    private int                   transferCursor;

    private boolean savedForCombat;

    protected boolean shouldUseBoatAssist() {
        return false;
    }

    public ExploreTask() { this(StructureMarker.Mode.EXPLORE, "Exploring..."); }

    /** Used by MysteryTask to share the same flow with a different label and pool. */
    protected ExploreTask(StructureMarker.Mode mode, String label) {
        this.mode = mode;
        this.displayLabel = label;
    }

    @Override public String displayName() { return displayLabel; }
    @Override public String id() { return mode == StructureMarker.Mode.MYSTERY ? "mystery" : "explore"; }

    /** These tasks have no fixed countdown; the HUD hides seconds-remaining. */
    @Override
    public boolean indefiniteDuration() { return true; }

    @Override
    public void start() {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            stage = Stage.DONE;
            return;
        }
        worldDimension = mc.world.getRegistryKey().getValue().toString();
        taskStartTick  = clientTick();
        stageStartTick = clientTick();
        chestsHandled.clear();
        chestQueue.clear();
        chosenType    = null;
        chosenMarker  = null;
        com.grammacrackers.chatpilot.travel.BoatTravelHelper.reset();
        ChatPilotClient.BARITONE.hardReset();
        // Open ground game: nudge Baritone into wandering while we scan.
        ChatPilotClient.BARITONE.run("explore");
        ChatPilotMod.LOGGER.info("[ChatPilot][Explore] Search started in mode {}", mode);
    }

    @Override
    public boolean tick() {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return false;

        // Hard hunt timeout safety net. TaskManager has its own hard cap from
        // maxTaskDurationSeconds but explore tasks live longer; we want the
        // bot to bail back home cleanly if it really cannot find anything.
        if (clientTick() - taskStartTick > HUNT_TIMEOUT_SECONDS * 20) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Explore] Hunt timeout reached, finishing");
            return true;
        }

        // Boat assist - cross water by boat instead of swimming. Works in any
        // stage: the goal is the located structure, or a point far ahead in
        // the current heading while still searching.
        if (shouldUseBoatAssist() && ChatPilotClient.CONFIG != null
                && ChatPilotClient.CONFIG.mysteryUseBoat) {
            BlockPos boatGoal = chosenMarker != null ? chosenMarker : farAheadPoint(mc);
            if (com.grammacrackers.chatpilot.travel.BoatTravelHelper.tickBoatAssist(mc, boatGoal)) {
                return false;
            }
        }

        switch (stage) {
            case SCAN_AND_HUNT     -> tickScanAndHunt(mc);
            case APPROACH          -> tickApproach(mc);
            case WANDER            -> tickWander(mc);
            case FIND_CHEST        -> tickFindChest(mc);
            case APPROACH_CHEST    -> tickApproachChest(mc);
            case OPEN_CHEST        -> tickOpenChest(mc);
            case LOOT              -> tickLoot(mc);
            case CLOSE_CHEST       -> tickCloseChest(mc);
            case DONE              -> { return true; }
        }
        return stage == Stage.DONE;
    }

    /* ---------- per-stage tick handlers ---------- */

    private void tickScanAndHunt(MinecraftClient mc) {
        // Re-issue Baritone's explore command periodically. Baritone's
        // "explore" picks a destination 1000 blocks away by default and
        // sometimes finishes early if pathing succeeds; reissuing keeps it
        // moving outward.
        if (clientTick() - lastExploreReissueTick > EXPLORE_REISSUE_TICKS
            && !ChatPilotClient.BARITONE.isActive()) {
            ChatPilotClient.BARITONE.run("explore");
            lastExploreReissueTick = clientTick();
        }

        if (clientTick() - lastScanTick < RESCAN_INTERVAL_TICKS) return;
        lastScanTick = clientTick();

        BlockPos marker = scanForUnvisitedMarker(mc);
        if (marker == null) return;

        // Lock on. The chosen marker stays our goal even if scanForMarker
        // would later find a closer one — switching mid-approach causes
        // baritone thrash on stream.
        ChatPilotClient.BARITONE.hardReset();
        chosenMarker = marker.toImmutable();
        chosenType   = identifyType(mc.world, chosenMarker);
        if (chosenType == null) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Explore] Marker found but type lookup failed at {}", chosenMarker);
            chosenMarker = null;
            return;
        }
        ChatPilotMod.LOGGER.info("[ChatPilot][Explore] Locked onto {} at {}",
            StructureMarker.describe(chosenType), chosenMarker);
        ChatPilotClient.BARITONE.gotoNear(chosenMarker, 3);
        enterStage(Stage.APPROACH);
    }

    private void tickApproach(MinecraftClient mc) {
        if (chosenMarker == null) { enterStage(Stage.SCAN_AND_HUNT); return; }

        // Re-issue the goal if Baritone went idle without arriving.
        double dist2 = mc.player.getBlockPos().getSquaredDistance(chosenMarker);
        if (dist2 < ARRIVAL_DIST * ARRIVAL_DIST) {
            ChatPilotClient.BARITONE.hardReset();
            ChatPilotMod.LOGGER.info("[ChatPilot][Explore] Arrived at {}", chosenMarker);
            enterStage(Stage.WANDER);
            return;
        }
        if (ticksInStage() > APPROACH_TIMEOUT_SECONDS * 20) {
            // Could not reach it. Mark visited anyway so we don't try the
            // same cluster forever, then go home.
            ChatPilotClient.BARITONE.hardReset();
            ChatPilotMod.LOGGER.info("[ChatPilot][Explore] Approach timeout, abandoning {}", chosenMarker);
            ChatPilotClient.VISITED.markVisited(chosenType.kind, worldDimension, chosenMarker);
            enterStage(Stage.DONE);
            return;
        }
        if (!ChatPilotClient.BARITONE.isPathing() && !ChatPilotClient.BARITONE.isActive()) {
            ChatPilotClient.BARITONE.gotoNear(chosenMarker, 3);
        }
    }

    private void tickWander(MinecraftClient mc) {
        if (chosenType == null || chosenMarker == null) { enterStage(Stage.DONE); return; }
        // Limit total time wandering so we don't run the whole task budget
        // standing on a pyramid floor.
        if (ticksInStage() > chosenType.exploreSeconds * 20) {
            // Done wandering, pivot to chest hunt. After all chests handled
            // we'll exit back to DONE.
            enterStage(Stage.FIND_CHEST);
            return;
        }
        // Quietly nudge Baritone with random goals around the marker so the
        // bot looks like it's exploring. Only redo this every few seconds.
        if (ticksInStage() % 80 == 0 || !ChatPilotClient.BARITONE.isActive()) {
            int dx = (int)((Math.random() - 0.5) * 2 * chosenType.exploreRadius);
            int dz = (int)((Math.random() - 0.5) * 2 * chosenType.exploreRadius);
            BlockPos goal = chosenMarker.add(dx, 0, dz);
            ChatPilotClient.BARITONE.gotoXZ(goal.getX(), goal.getZ());
        }
    }

    private void tickFindChest(MinecraftClient mc) {
        if (chosenType == null || chosenMarker == null) { enterStage(Stage.DONE); return; }
        // Refresh queue from world chunks within radius of the marker.
        if (chestQueue.isEmpty()) {
            for (BlockPos p : BlockPos.iterate(
                    chosenMarker.add(-chosenType.exploreRadius, -8, -chosenType.exploreRadius),
                    chosenMarker.add(chosenType.exploreRadius, 8, chosenType.exploreRadius))) {
                try {
                    if (mc.world.getBlockEntity(p) instanceof ChestBlockEntity) {
                        BlockPos im = p.toImmutable();
                        if (!chestsHandled.contains(im)) chestQueue.add(im);
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (chestQueue.isEmpty()) {
            // No chests left here. Mark visited and finish.
            ChatPilotClient.VISITED.markVisited(chosenType.kind, worldDimension, chosenMarker);
            enterStage(Stage.DONE);
            return;
        }
        currentChest = chestQueue.pollFirst();
        chestsHandled.add(currentChest);
        ChatPilotClient.BARITONE.hardReset();
        ChatPilotClient.BARITONE.gotoNear(currentChest, 1);
        enterStage(Stage.APPROACH_CHEST);
    }

    private void tickApproachChest(MinecraftClient mc) {
        if (currentChest == null) { enterStage(Stage.FIND_CHEST); return; }
        double dist2 = mc.player.getBlockPos().getSquaredDistance(currentChest);
        if (dist2 < 9 || ticksInStage() > 25 * 20) {
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.OPEN_CHEST);
        }
    }

    private void tickOpenChest(MinecraftClient mc) {
        if (currentChest == null) { enterStage(Stage.FIND_CHEST); return; }
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            transferCursor = 0;
            enterStage(Stage.LOOT);
            return;
        }
        if (ticksInStage() > CHEST_OPEN_TIMEOUT_TICKS) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Explore] Could not open chest at {}, skipping", currentChest);
            enterStage(Stage.FIND_CHEST);
            return;
        }
        if (ticksInStage() % 10 == 0) {
            // Verify the block still exists. Could have been broken by combat.
            if (!(mc.world.getBlockEntity(currentChest) instanceof ChestBlockEntity)) {
                enterStage(Stage.FIND_CHEST);
                return;
            }
            rightClickBlock(mc, currentChest);
        }
    }

    private void tickLoot(MinecraftClient mc) {
        ScreenHandler sh = mc.player.currentScreenHandler;
        if (!(sh instanceof GenericContainerScreenHandler chest)) {
            enterStage(Stage.FIND_CHEST);
            return;
        }
        int chestSlots = chest.getRows() * 9;
        // Quick-move chest stacks into the player. Two slots per tick.
        if (ticksInStage() % CHEST_LOOT_INTERVAL_TICKS != 0) return;
        if (transferCursor >= chestSlots) {
            enterStage(Stage.CLOSE_CHEST);
            return;
        }
        ItemStack stack = sh.getSlot(transferCursor).getStack();
        if (!stack.isEmpty()) {
            try {
                mc.interactionManager.clickSlot(sh.syncId, transferCursor, 0,
                    SlotActionType.QUICK_MOVE, mc.player);
            } catch (Throwable t) {
                ChatPilotMod.LOGGER.warn("[ChatPilot][Explore] Loot click failed", t);
            }
        }
        transferCursor++;
    }

    private void tickCloseChest(MinecraftClient mc) {
        if (mc.player != null) mc.player.closeHandledScreen();
        currentChest = null;
        enterStage(Stage.FIND_CHEST);
    }

    /* ---------- helpers ---------- */

    private void enterStage(Stage next) {
        stage = next;
        stageStartTick = clientTick();
    }

    /**
     * Scans all blocks within a radius of the player for any marker block
     * matching this task's mode. Returns the closest unvisited match, or
     * null if none is in range.
     *
     * The vertical scan range is small to keep the scan cost reasonable.
     * Horizontal range is larger because Baritone explore loads chunks
     * outward in a circle.
     */
    private BlockPos scanForUnvisitedMarker(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return null;
        BlockPos here = mc.player.getBlockPos();

        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;

        BlockPos lo = here.add(-MARKER_SCAN_HORIZ, -MARKER_SCAN_VERT, -MARKER_SCAN_HORIZ);
        BlockPos hi = here.add( MARKER_SCAN_HORIZ,  MARKER_SCAN_VERT,  MARKER_SCAN_HORIZ);
        for (BlockPos p : BlockPos.iterate(lo, hi)) {
            try {
                Identifier id = Registries.BLOCK.getId(mc.world.getBlockState(p).getBlock());
                if (id == null) continue;
                String path = id.getPath();
                StructureMarker.Type t = StructureMarker.matchType(mode, path);
                if (t == null) continue;

                BlockPos im = p.toImmutable();

                // Skip if already visited a same-kind structure near here.
                if (ChatPilotClient.VISITED.isNearVisited(t.kind, worldDimension, im)) continue;

                // Skip structures at or right next to home. The explore task
                // should head OUT to find somewhere new, not turn around and
                // walk back to the structure the house sits in/next to.
                if (isTooCloseToHome(im)) continue;

                // Avoid water structures (shipwrecks etc.) - this filter
                // catches anything where the marker block sits in or near
                // water columns. Marker types that are inherently fine in
                // water (none currently) opt out via avoidIfWaterAround=false.
                if (t.avoidIfWaterAround
                    && StructureMarker.hasWaterAround(mc.world, im, WATER_AVOID_RADIUS)) {
                    continue;
                }

                double d2 = im.getSquaredDistance(here);
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = im;
                }
            } catch (Throwable ignored) {}
        }
        return best;
    }

    /**
     * True if a marker sits within {@link #MARKER_MIN_DIST_FROM_HOME} blocks
     * (horizontally) of the home bed. Such markers are skipped during the scan
     * so the bot never locks onto the structure its own house sits in/next to
     * and walks back home instead of exploring outward.
     */
    private static boolean isTooCloseToHome(BlockPos pos) {
        if (ChatPilotClient.HOME == null || !ChatPilotClient.HOME.hasHome()) {
            return false;
        }

        BlockPos bed = ChatPilotClient.HOME.getBedPos();
        if (bed == null) {
            return false;
        }

        double dx = pos.getX() - bed.getX();
        double dz = pos.getZ() - bed.getZ();
        return dx * dx + dz * dz
                < (double) MARKER_MIN_DIST_FROM_HOME * MARKER_MIN_DIST_FROM_HOME;
    }

    private StructureMarker.Type identifyType(World world, BlockPos p) {
        try {
            String path = Registries.BLOCK.getId(world.getBlockState(p).getBlock()).getPath();
            return StructureMarker.matchType(mode, path);
        } catch (Throwable t) {
            return null;
        }
    }

    private void rightClickBlock(MinecraftClient mc, BlockPos pos) {
        if (mc.player == null || mc.interactionManager == null) return;
        Vec3d hit = Vec3d.ofCenter(pos);
        Vec3d eye = mc.player.getEyePos();
        double dx = hit.x - eye.x, dy = hit.y - eye.y, dz = hit.z - eye.z;
        double horiz = Math.sqrt(dx*dx + dz*dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
        BlockHitResult hr = new BlockHitResult(hit, Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hr);
    }

    @Override
    public boolean onStuck() {
        ChatPilotMod.LOGGER.info("[ChatPilot][Explore] Stall in {}, recovering", stage);
        ChatPilotClient.BARITONE.hardReset();
        switch (stage) {
            case SCAN_AND_HUNT -> {
                ChatPilotClient.BARITONE.run("explore");
                lastExploreReissueTick = clientTick();
                return true;
            }
            case APPROACH -> {
                if (chosenMarker != null) {
                    ChatPilotClient.BARITONE.gotoNear(chosenMarker, 4);
                    return true;
                }
                enterStage(Stage.SCAN_AND_HUNT);
                return true;
            }
            case WANDER -> {
                // Skip ahead to looting chests.
                enterStage(Stage.FIND_CHEST);
                return true;
            }
            case APPROACH_CHEST, OPEN_CHEST -> {
                MinecraftClient.getInstance().player.closeHandledScreen();
                currentChest = null;
                enterStage(Stage.FIND_CHEST);
                return true;
            }
            case LOOT, CLOSE_CHEST -> {
                MinecraftClient.getInstance().player.closeHandledScreen();
                currentChest = null;
                enterStage(Stage.FIND_CHEST);
                return true;
            }
            case FIND_CHEST -> {
                // Already trying to scan/path; finishing is fine.
                if (chosenType != null && chosenMarker != null) {
                    ChatPilotClient.VISITED.markVisited(chosenType.kind, worldDimension, chosenMarker);
                }
                enterStage(Stage.DONE);
                return true;
            }
            case DONE -> { return false; }
        }
        return false;
    }

    @Override
    public void onCombatStart() {
        savedForCombat = true;
        ChatPilotClient.BARITONE.stop();
    }

    @Override
    public void onCombatEnd() {
        if (!savedForCombat) return;
        savedForCombat = false;
        switch (stage) {
            case APPROACH -> {
                if (chosenMarker != null) ChatPilotClient.BARITONE.gotoNear(chosenMarker, 3);
            }
            case APPROACH_CHEST -> {
                if (currentChest != null) ChatPilotClient.BARITONE.gotoNear(currentChest, 1);
            }
            case SCAN_AND_HUNT -> {
                ChatPilotClient.BARITONE.run("explore");
                lastExploreReissueTick = clientTick();
            }
            default -> {}
        }
    }

    @Override
    public void cancel() {
        com.grammacrackers.chatpilot.travel.BoatTravelHelper.reset();
        ChatPilotClient.BARITONE.hardReset();
        var p = MinecraftClient.getInstance().player;
        if (p != null) p.closeHandledScreen();
    }

    private int ticksInStage() { return clientTick() - stageStartTick; }
    private static int clientTick() {
        return (int) (com.grammacrackers.chatpilot.event.TickClock.now() & 0x7fffffff);
    }

    /**
     * A point ~150 blocks ahead of the bot in its current heading. Used as the
     * boat-travel goal while still searching (no structure locked yet) so the
     * bot boats straight across water rather than swimming it.
     */
    private static BlockPos farAheadPoint(MinecraftClient mc) {
        double rad = Math.toRadians(mc.player.getYaw());
        int dx = (int) Math.round(-Math.sin(rad) * 150.0);
        int dz = (int) Math.round(Math.cos(rad) * 150.0);
        return mc.player.getBlockPos().add(dx, 0, dz);
    }
}
