package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Explore Nether task (votable).
 *
 * Flow:
 *   FIND_PORTAL      - locate the nearest active nether portal in the Overworld
 *                      and path to it. If none is in range, wander (Baritone
 *                      "explore") and re-scan until one shows up.
 *   ENTER_PORTAL     - step into the portal and wait for the dimension change.
 *   MINE_NETHER      - mine nether gold ore, nether quartz ore and ancient
 *                      debris (netherite) for a bounded window.
 *   RETURN_TO_PORTAL - walk back to the portal the bot arrived through.
 *   EXIT_PORTAL      - step into that portal and wait for the trip back to the
 *                      Overworld.
 *   DONE             - hand off to TaskManager, which runs the normal
 *                      return-home-and-deposit chain from here, so the bot
 *                      walks the rest of the way home.
 *
 * Time budget: the task is marked {@link #indefiniteDuration()} so TaskManager
 * gives it the long indefinite ceiling, but it also caps its own phases so the
 * bot is always back in the Overworld well before that ceiling could fire
 * while it is still in the Nether.
 *
 * Portal traversal: Baritone covers the long approach to a portal; the last
 * few blocks are done with manual key control so the bot can be parked
 * precisely inside the 1-block-thick portal while the transfer timer runs.
 */
public class NetherTask implements Task {

    private enum Stage { FIND_PORTAL, ENTER_PORTAL, MINE_NETHER, RETURN_TO_PORTAL, EXIT_PORTAL, DONE }

    /** Ores the bot mines while in the Nether: gold, quartz and netherite. */
    private static final String[] NETHER_ORE_BLOCKS =
            { "nether_gold_ore", "nether_quartz_ore", "ancient_debris" };

    /* ---------- timing knobs (ticks; 20 ticks = 1 second) ---------- */
    private static final int FIND_PORTAL_TIMEOUT_TICKS        = 90 * 20;
    private static final int ENTER_TIMEOUT_TICKS              = 45 * 20;
    /** If a portal exit stalls this long, re-path to it from scratch. */
    private static final int EXIT_TIMEOUT_TICKS               = 45 * 20;
    private static final int MINE_STAGE_TICKS                 = 150 * 20;
    /** Total elapsed cap that forces a return so we exit before TaskManager's ceiling. */
    private static final int MINE_HARD_END_TICKS              = 270 * 20;
    private static final int NETHER_ARRIVAL_SCAN_GIVEUP_TICKS = 12 * 20;
    private static final int RESCAN_INTERVAL_TICKS            = 40;
    private static final int EXPLORE_REISSUE_TICKS            = 200;

    /* ---------- portal scan / traversal knobs ---------- */
    private static final int    PORTAL_SCAN_HORIZ        = 48;
    private static final int    PORTAL_SCAN_VERT         = 16;
    private static final int    PORTAL_FLOOR_MAX_DESCENT = 6;
    /** Squared-distance threshold for "arrived at" a portal. */
    private static final int    ARRIVAL_DIST             = 3;
    /** Within this horizontal distance, hand the final approach to manual keys. */
    private static final double MANUAL_RANGE             = 3.0;
    /** Drifted past this horizontal distance: re-path with Baritone. */
    private static final double MANUAL_ABANDON           = 6.0;

    /* ---------- portal-area mining protection ---------- */
    /** The bot keeps its ore-mining at least this far (horizontally) from the
     *  return portal. With the bot's block-breaking reach (~4-5 blocks) this
     *  leaves a clear zone of roughly 10+ blocks around the portal untouched. */
    private static final double PORTAL_KEEP_CLEAR  = 16.0;
    /** Once escorted out, mining only resumes past this distance, so the bot
     *  does not jitter back and forth on the keep-clear boundary. */
    private static final double PORTAL_RESUME_DIST = 22.0;
    /** Where the bot is escorted to when it drifts too close to the portal. */
    private static final int    MINE_ANCHOR_DIST   = 26;

    private Stage stage = Stage.FIND_PORTAL;
    private int   taskStartTick;
    private int   stageStartTick;
    private int   lastScanTick;
    private int   lastExploreReissueTick;

    /** Portal used to enter the Nether (Overworld side). */
    private BlockPos overworldPortal;
    /** Portal used to leave the Nether (Nether side); recorded on arrival. */
    private BlockPos netherPortal;
    /** Block the bot first stood on when it crossed into the Nether. */
    private BlockPos netherEntryPos;
    /** True once the final approach to a portal is under manual key control. */
    private boolean  manualPortal;
    /** True while the bot is being walked back out of the portal keep-clear zone. */
    private boolean  escortingFromPortal;
    private boolean  savedForCombat;

    @Override
    public String displayName() {
        return switch (stage) {
            case FIND_PORTAL      -> "Heading to the Nether";
            case ENTER_PORTAL     -> "Entering the Nether";
            case MINE_NETHER      -> "Mining in the Nether";
            case RETURN_TO_PORTAL -> "Leaving the Nether";
            case EXIT_PORTAL      -> "Leaving the Nether";
            case DONE             -> "Returning home";
        };
    }

    @Override
    public String id() { return "nether"; }

    /** No fixed countdown; runtime is bounded by what the bot does, not a clock. */
    @Override
    public boolean indefiniteDuration() { return true; }

    @Override
    public void start() {
        taskStartTick = clientTick();
        overworldPortal = null;
        netherPortal = null;
        netherEntryPos = null;
        manualPortal = false;
        escortingFromPortal = false;
        savedForCombat = false;
        lastScanTick = 0;
        lastExploreReissueTick = 0;
        ChatPilotClient.BARITONE.hardReset();
        enterStage(Stage.FIND_PORTAL);
        ChatPilotMod.LOGGER.info("[ChatPilot][Nether] Explore Nether started, searching for a portal");
    }

    @Override
    public boolean tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return false;

        switch (stage) {
            case FIND_PORTAL      -> tickFindPortal(mc);
            case ENTER_PORTAL     -> tickEnterPortal(mc);
            case MINE_NETHER      -> tickMineNether(mc);
            case RETURN_TO_PORTAL -> tickReturnToPortal(mc);
            case EXIT_PORTAL      -> tickExitPortal(mc);
            case DONE             -> { return true; }
        }
        return stage == Stage.DONE;
    }

    /* ---------- per-stage tick handlers ---------- */

    private void tickFindPortal(MinecraftClient mc) {
        if (clientTick() - taskStartTick > FIND_PORTAL_TIMEOUT_TICKS) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Nether] No portal found in time, abandoning");
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.DONE);
            return;
        }

        if (clientTick() - lastScanTick >= RESCAN_INTERVAL_TICKS) {
            lastScanTick = clientTick();
            BlockPos portal = scanForNearestPortal(mc);
            if (portal != null) {
                overworldPortal = portalFloor(mc.world, portal);
                ChatPilotMod.LOGGER.info("[ChatPilot][Nether] Portal located at {}, heading in", overworldPortal);
                ChatPilotClient.BARITONE.hardReset();
                ChatPilotClient.BARITONE.gotoBlock(overworldPortal);
                enterStage(Stage.ENTER_PORTAL);
                return;
            }
        }

        // No portal in range yet - wander to load fresh chunks and look again.
        if (!ChatPilotClient.BARITONE.isActive()
                && clientTick() - lastExploreReissueTick > EXPLORE_REISSUE_TICKS) {
            ChatPilotClient.BARITONE.run("explore");
            lastExploreReissueTick = clientTick();
        }
    }

    private void tickEnterPortal(MinecraftClient mc) {
        if (overworldPortal == null) {
            enterStage(Stage.DONE);
            return;
        }

        if (inNether(mc)) {
            onArrivedInNether(mc);
            return;
        }

        if (clientTick() - stageStartTick > ENTER_TIMEOUT_TICKS) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Nether] Could not enter the portal in time, abandoning");
            releaseKeys(mc);
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.DONE);
            return;
        }

        traversePortal(mc, overworldPortal);
    }

    private void onArrivedInNether(MinecraftClient mc) {
        releaseKeys(mc);
        ChatPilotClient.BARITONE.hardReset();

        BlockPos here = mc.player.getBlockPos().toImmutable();
        netherEntryPos = here;
        // If we landed inside the linked portal (the normal case), record it now
        // so we know exactly where to come back to. Otherwise MINE_NETHER scans.
        netherPortal = standingInPortal(mc) ? portalFloor(mc.world, here) : null;

        ChatPilotMod.LOGGER.info("[ChatPilot][Nether] Arrived in the Nether at {}", here);
        enterStage(Stage.MINE_NETHER);
    }

    private void tickMineNether(MinecraftClient mc) {
        if (inOverworld(mc)) {
            // Unexpected early trip back to the Overworld - just finish cleanly.
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.DONE);
            return;
        }

        // Lock in the portal we arrived through before wandering off to mine.
        if (netherPortal == null) {
            BlockPos p = scanForNearestPortal(mc);
            if (p != null) {
                netherPortal = portalFloor(mc.world, p);
                ChatPilotMod.LOGGER.info("[ChatPilot][Nether] Return portal located at {}", netherPortal);
            } else if (clientTick() - stageStartTick > NETHER_ARRIVAL_SCAN_GIVEUP_TICKS) {
                netherPortal = netherEntryPos != null
                        ? netherEntryPos
                        : mc.player.getBlockPos().toImmutable();
                ChatPilotMod.LOGGER.warn(
                        "[ChatPilot][Nether] Portal scan failed, using arrival point {}", netherPortal);
            } else {
                // Give chunks a moment to load before we start mining away.
                return;
            }
        }

        boolean stageOver  = clientTick() - stageStartTick > MINE_STAGE_TICKS;
        boolean budgetOver = clientTick() - taskStartTick  > MINE_HARD_END_TICKS;
        if (stageOver || budgetOver) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Nether] Mining window over, heading back to the portal");
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.RETURN_TO_PORTAL);
            return;
        }

        // Keep ore-mining clear of the portal so the bot never craters or
        // breaks blocks in the protected zone around it. Hysteresis between
        // KEEP_CLEAR and RESUME_DIST stops jitter on the boundary.
        double pdx = mc.player.getX() - (netherPortal.getX() + 0.5);
        double pdz = mc.player.getZ() - (netherPortal.getZ() + 0.5);
        double portalDist2 = pdx * pdx + pdz * pdz;

        if (portalDist2 < PORTAL_KEEP_CLEAR * PORTAL_KEEP_CLEAR) {
            escortingFromPortal = true;
        } else if (portalDist2 > PORTAL_RESUME_DIST * PORTAL_RESUME_DIST) {
            escortingFromPortal = false;
        }

        if (escortingFromPortal) {
            // Too close to the portal - stop mining and walk radially outward
            // until well clear, then mining resumes out there.
            if (ChatPilotClient.BARITONE.isMining()) {
                ChatPilotClient.BARITONE.hardReset();
            }
            if (!ChatPilotClient.BARITONE.isPathing()) {
                BlockPos anchor = mineAnchorAwayFromPortal(mc);
                ChatPilotClient.BARITONE.gotoXZ(anchor.getX(), anchor.getZ());
            }
            return;
        }

        if (!ChatPilotClient.BARITONE.isMining()) {
            ChatPilotClient.BARITONE.mineBlocks(0, NETHER_ORE_BLOCKS);
        }
    }

    /**
     * A point {@link #MINE_ANCHOR_DIST} blocks from the portal, in the
     * direction the bot currently lies from it, so the escort path always
     * moves radially outward and never back across the portal.
     */
    private BlockPos mineAnchorAwayFromPortal(MinecraftClient mc) {
        double dx = mc.player.getX() - (netherPortal.getX() + 0.5);
        double dz = mc.player.getZ() - (netherPortal.getZ() + 0.5);
        double len = Math.sqrt(dx * dx + dz * dz);

        if (len < 0.5) {
            // Bot basically on the portal - pick a fixed default heading.
            dx = 1.0;
            dz = 0.0;
            len = 1.0;
        }

        int ax = netherPortal.getX() + (int) Math.round(dx / len * MINE_ANCHOR_DIST);
        int az = netherPortal.getZ() + (int) Math.round(dz / len * MINE_ANCHOR_DIST);
        return new BlockPos(ax, netherPortal.getY(), az);
    }

    private void tickReturnToPortal(MinecraftClient mc) {
        if (inOverworld(mc)) {
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.DONE);
            return;
        }

        if (netherPortal == null) {
            BlockPos p = scanForNearestPortal(mc);
            netherPortal = p != null
                    ? portalFloor(mc.world, p)
                    : (netherEntryPos != null ? netherEntryPos : mc.player.getBlockPos().toImmutable());
        }

        if (standingInPortal(mc)
                || mc.player.getBlockPos().getSquaredDistance(netherPortal) <= ARRIVAL_DIST * ARRIVAL_DIST) {
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.EXIT_PORTAL);
            return;
        }

        if (!ChatPilotClient.BARITONE.isPathing()) {
            ChatPilotClient.BARITONE.gotoBlock(netherPortal);
        }
    }

    private void tickExitPortal(MinecraftClient mc) {
        if (inOverworld(mc)) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Nether] Back in the Overworld, handing off to return-home");
            releaseKeys(mc);
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.DONE);
            return;
        }

        // Stalled trying to step into the portal: drop back to RETURN_TO_PORTAL
        // so Baritone re-paths to it cleanly instead of hanging here forever.
        if (clientTick() - stageStartTick > EXIT_TIMEOUT_TICKS) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Nether] Exit portal stalled, re-pathing to it");
            releaseKeys(mc);
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.RETURN_TO_PORTAL);
            return;
        }

        if (netherPortal == null) {
            BlockPos p = scanForNearestPortal(mc);
            if (p != null) {
                netherPortal = portalFloor(mc.world, p);
            } else {
                // Lost track of the portal - drop back to searching for it.
                enterStage(Stage.RETURN_TO_PORTAL);
                return;
            }
        }

        traversePortal(mc, netherPortal);
    }

    /* ---------- portal traversal ---------- */

    /**
     * Walks the bot into {@code target} (a portal floor block) and holds it
     * there so the dimension-transfer timer can run. Baritone covers the long
     * approach; the last few blocks use manual key control so the bot can be
     * parked precisely inside the 1-block-thick portal.
     */
    private void traversePortal(MinecraftClient mc, BlockPos target) {
        double dx = (target.getX() + 0.5) - mc.player.getX();
        double dz = (target.getZ() + 0.5) - mc.player.getZ();
        double horiz2 = dx * dx + dz * dz;

        if (!manualPortal) {
            if (horiz2 <= MANUAL_RANGE * MANUAL_RANGE) {
                // Close enough - take over with manual movement.
                ChatPilotClient.BARITONE.hardReset();
                manualPortal = true;
            } else {
                releaseKeys(mc);
                if (!ChatPilotClient.BARITONE.isPathing()) {
                    ChatPilotClient.BARITONE.gotoBlock(target);
                }
                return;
            }
        }

        // Drifted well away from the portal - hand back to Baritone to re-path.
        if (horiz2 > MANUAL_ABANDON * MANUAL_ABANDON) {
            manualPortal = false;
            releaseKeys(mc);
            return;
        }

        if (standingInPortal(mc)) {
            // Inside the portal: stop walking and let momentum die so the bot
            // settles in place while the transfer timer counts down.
            releaseKeys(mc);
        } else {
            // Not in yet: walk straight at the portal block.
            walkInto(mc, target);
        }
    }

    private static void walkInto(MinecraftClient mc, BlockPos target) {
        if (mc.player == null || mc.options == null) return;

        double dx = (target.getX() + 0.5) - mc.player.getX();
        double dz = (target.getZ() + 0.5) - mc.player.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);

        mc.player.setYaw(yaw);
        mc.player.setHeadYaw(yaw);
        mc.player.setPitch(0.0f);

        mc.options.forwardKey.setPressed(true);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    private static void releaseKeys(MinecraftClient mc) {
        if (mc == null || mc.options == null) return;

        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    /* ---------- portal helpers ---------- */

    private static boolean standingInPortal(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return false;
        BlockPos feet = mc.player.getBlockPos();
        return mc.world.getBlockState(feet).isOf(Blocks.NETHER_PORTAL)
                || mc.world.getBlockState(feet.up()).isOf(Blocks.NETHER_PORTAL);
    }

    /** Descends from a portal block to the lowest portal block above the floor. */
    private static BlockPos portalFloor(World world, BlockPos p) {
        BlockPos floor = p.toImmutable();
        for (int i = 0; i < PORTAL_FLOOR_MAX_DESCENT; i++) {
            BlockPos below = floor.down();
            if (world.getBlockState(below).isOf(Blocks.NETHER_PORTAL)) {
                floor = below;
            } else {
                break;
            }
        }
        return floor;
    }

    /** Nearest nether portal block to the player within the scan box, or null. */
    private static BlockPos scanForNearestPortal(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return null;

        BlockPos here = mc.player.getBlockPos();
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;

        BlockPos lo = here.add(-PORTAL_SCAN_HORIZ, -PORTAL_SCAN_VERT, -PORTAL_SCAN_HORIZ);
        BlockPos hi = here.add( PORTAL_SCAN_HORIZ,  PORTAL_SCAN_VERT,  PORTAL_SCAN_HORIZ);

        for (BlockPos p : BlockPos.iterate(lo, hi)) {
            try {
                if (mc.world.getBlockState(p).isOf(Blocks.NETHER_PORTAL)) {
                    double d2 = p.getSquaredDistance(here);
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = p.toImmutable();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return best;
    }

    private static boolean inNether(MinecraftClient mc) {
        return mc.world != null && mc.world.getRegistryKey().equals(World.NETHER);
    }

    private static boolean inOverworld(MinecraftClient mc) {
        return mc.world != null && mc.world.getRegistryKey().equals(World.OVERWORLD);
    }

    /* ---------- interrupts ---------- */

    @Override
    public boolean onStuck() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ChatPilotMod.LOGGER.info("[ChatPilot][Nether] Stall recovery in stage {}", stage);
        ChatPilotClient.BARITONE.hardReset();

        switch (stage) {
            case FIND_PORTAL -> {
                ChatPilotClient.BARITONE.run("explore");
                lastExploreReissueTick = clientTick();
                return true;
            }
            case ENTER_PORTAL -> {
                manualPortal = false;
                if (overworldPortal != null) {
                    ChatPilotClient.BARITONE.gotoBlock(overworldPortal);
                }
                return true;
            }
            case MINE_NETHER -> {
                // Next tickMineNether re-issues the right action - escort away
                // from the portal, or mine - based on the current distance.
                return true;
            }
            case RETURN_TO_PORTAL -> {
                // The recorded portal may be unreachable - re-scan for any portal.
                if (mc != null && mc.world != null) {
                    BlockPos p = scanForNearestPortal(mc);
                    if (p != null) {
                        netherPortal = portalFloor(mc.world, p);
                    }
                }
                if (netherPortal != null) {
                    ChatPilotClient.BARITONE.gotoBlock(netherPortal);
                }
                return true;
            }
            case EXIT_PORTAL -> {
                manualPortal = false;
                if (netherPortal != null) {
                    ChatPilotClient.BARITONE.gotoBlock(netherPortal);
                }
                return true;
            }
            case DONE -> { return false; }
        }
        return false;
    }

    @Override
    public void onCombatStart() {
        savedForCombat = true;
        releaseKeys(MinecraftClient.getInstance());
        ChatPilotClient.BARITONE.stop();
    }

    @Override
    public void onCombatEnd() {
        if (!savedForCombat) return;
        savedForCombat = false;
        // Re-approach portals cleanly after a fight rather than from mid-walk.
        manualPortal = false;

        switch (stage) {
            case FIND_PORTAL -> ChatPilotClient.BARITONE.run("explore");
            case ENTER_PORTAL -> {
                if (overworldPortal != null) ChatPilotClient.BARITONE.gotoBlock(overworldPortal);
            }
            // MINE_NETHER: tickMineNether restarts mining (or the portal
            // escort) on its own, so nothing to re-issue here.
            case RETURN_TO_PORTAL, EXIT_PORTAL -> {
                if (netherPortal != null) ChatPilotClient.BARITONE.gotoBlock(netherPortal);
            }
            default -> {}
        }
    }

    @Override
    public void cancel() {
        releaseKeys(MinecraftClient.getInstance());
        ChatPilotClient.BARITONE.hardReset();
    }

    /* ---------- helpers ---------- */

    private void enterStage(Stage next) {
        ChatPilotMod.LOGGER.info("[ChatPilot][Nether] Stage {} -> {}", stage, next);
        stage = next;
        stageStartTick = clientTick();
        manualPortal = false;
        releaseKeys(MinecraftClient.getInstance());
    }

    private static int clientTick() {
        return (int) (com.grammacrackers.chatpilot.event.TickClock.now() & 0x7fffffff);
    }
}
