package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Fishing task. v1.2.0 replacement for the old wood-gathering vote slot.
 *
 * Why fishing instead of wood: streams want visible progression that doesn't
 * accumulate too many resources. Fishing satisfies all three:
 *   - Visible: bobber casts and bobs in real time, splash on each catch.
 *   - Progressive: catches arrive one at a time, chat watches a counter tick.
 *   - Low impact on inventory: fish are food (not crafting), and rare
 *     treasures (saddles, name tags, enchanted books) give chat hype moments
 *     without flooding the chest.
 *
 * Stages:
 *   SCAN_WATER      - find a water surface block within scan radius
 *   WALK_TO_WATER   - Baritone gotoNear the water position
 *   EQUIP_ROD       - find a fishing_rod in inventory and select it
 *   AIM_AND_CAST    - face the water, right-click to cast
 *   WAITING         - watch the bobber's velocity for a bite
 *   REELING         - right-click again to reel in the catch
 *   SETTLE          - brief pause before the next cast
 *   EXPLORE_OUTWARD - if no water nearby, run Baritone explore and rescan
 *   DONE            - catch target reached or task ended
 *
 * Catch detection: the FishingBobberEntity dips its Y velocity below
 * {@code fishingBiteVelocityY} (default -0.04) when a fish bites. We watch
 * for that on the client side; no server-side mod needed. Vanilla average
 * bite time is 5-30 seconds with a luck-of-the-sea rod, so we cap WAITING at
 * {@code fishingMaxWaitTicks} and recast on timeout.
 *
 * Requirements: the bot needs a fishing rod somewhere in inventory. The task
 * checks hotbar first, then main inventory (auto-swaps to hotbar slot 8).
 * If no rod is found, the task ends quickly so the next vote can pick
 * something else.
 */
public class FishingTask implements Task {

    private enum Stage {
        SCAN_WATER,
        WALK_TO_WATER,
        EQUIP_ROD,
        AIM_AND_CAST,
        WAITING,
        REELING,
        SETTLE,
        EXPLORE_OUTWARD,
        DONE
    }

    /** Hard cap on the explore-for-water cycles before we give up the task. */
    private static final int MAX_EXPLORE_CYCLES         = 3;
    /** How long to walk via Baritone explore each cycle. */
    private static final int EXPLORE_DURATION_TICKS     = 18 * 20;
    /** Re-issue Baritone goto if the bot stops without arriving. */
    private static final int APPROACH_TIMEOUT_TICKS     = 25 * 20;
    /** How close (squared distance) counts as "arrived at water". */
    private static final int ARRIVAL_DIST_SQ            = 16;
    /** Settle window after each reel before casting again. */
    private static final int CAST_GUARD_TICKS           = 30;

    private Stage   stage = Stage.SCAN_WATER;
    private int     stageStartTick;
    private int     taskStartTick;
    private int     catches;
    private int     exploreCycles;
    private int     waitingStartTick;
    private int     rodSelectedSlot = -1;
    private boolean savedForCombat;
    private BlockPos waterTarget;

    @Override public String displayName() { return "Fishing  catches: " + catches; }
    @Override public String id() { return "fish"; }

    @Override
    public void start() {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            stage = Stage.DONE;
            return;
        }
        taskStartTick  = clientTick();
        stageStartTick = clientTick();
        catches        = 0;
        exploreCycles  = 0;
        waterTarget    = null;
        ChatPilotClient.BARITONE.hardReset();
        enterStage(Stage.SCAN_WATER);
        ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Task started");
    }

    @Override
    public boolean tick() {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return false;

        // ====================================================================
        // OCEAN FIX 1: TREAD WATER FOR BUOYANCY
        // ====================================================================
        // If the bot is inside water or submerged, forcefully hold the jump key
        // to float directly up to the surface and stay there.
        if (mc.player.isTouchingWater() || mc.player.isInSwimmingPose()) {
            mc.options.jumpKey.setPressed(true);
        } else {
            // Re-release standard control handling if we step onto dry land
            // but only if Baritone isn't trying to use jump for pathing
            if (!ChatPilotClient.BARITONE.isPathing()) {
                mc.options.jumpKey.setPressed(false);
            }
        }
        // ====================================================================

        switch (stage) {
            case SCAN_WATER       -> tickScanWater(mc);
            case WALK_TO_WATER    -> tickWalkToWater(mc);
            case EQUIP_ROD        -> tickEquipRod(mc);
            case AIM_AND_CAST     -> tickAimAndCast(mc);
            case WAITING          -> tickWaiting(mc);
            case REELING          -> tickReeling(mc);
            case SETTLE           -> tickSettle();
            case EXPLORE_OUTWARD  -> tickExploreOutward();
            case DONE             -> { return true; }
        }
        return stage == Stage.DONE;
    }

    /* ---------- per-stage handlers ---------- */

    private void tickScanWater(MinecraftClient mc) {

        // ====================================================================
        // OCEAN FIX 2: SHORT-CIRCUIT FOR OPEN SEA FISHING
        // ====================================================================
        if (mc.player.isTouchingWater() && mc.world.getFluidState(mc.player.getBlockPos()).isStill()) {
            // We are already floating in water! Target a block 4 blocks directly ahead of our gaze
            var lookVec = mc.player.getRotationVec(1.0f);
            BlockPos forwardWater = mc.player.getBlockPos().add(
                    (int)(lookVec.x * 4),
                    0,
                    (int)(lookVec.z * 4)
            );

            waterTarget = forwardWater;
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Deep Ocean detected! Fishing in-place at surface.");
            enterStage(Stage.EQUIP_ROD); // Skip scanning and walking entirely!
            return;
        }
        // ====================================================================

        BlockPos surface = findWaterSurface(mc);
        if (surface != null) {
            waterTarget = surface;
            ChatPilotClient.BARITONE.hardReset();
            ChatPilotClient.BARITONE.gotoNear(surface, 2);
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Water surface at {}, walking to it", surface);
            enterStage(Stage.WALK_TO_WATER);
            return;
        }
        // No water nearby: run explore to push outward into fresh chunks.
        if (exploreCycles >= MAX_EXPLORE_CYCLES) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Could not find water after {} explores, ending",
                exploreCycles);
            enterStage(Stage.DONE);
            return;
        }
        exploreCycles++;
        ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] No water nearby, exploring outward (cycle {}/{})",
            exploreCycles, MAX_EXPLORE_CYCLES);
        ChatPilotClient.BARITONE.hardReset();
        ChatPilotClient.BARITONE.run("explore");
        enterStage(Stage.EXPLORE_OUTWARD);
    }

    private void tickWalkToWater(MinecraftClient mc) {
        if (waterTarget == null) { enterStage(Stage.SCAN_WATER); return; }
        double dist2 = mc.player.getBlockPos().getSquaredDistance(waterTarget);

        // FIX: Instead of checking ARRIVAL_DIST_SQ (16), we check if it's within 4-6 blocks squared.
        // This ensures the bot keeps walking until it is tightly positioned by the water.
        if (dist2 <= 6.0) {
            ChatPilotClient.BARITONE.stop(); // Stop Baritone immediately
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.EQUIP_ROD);
            return;
        }
        if (ticksInStage() > APPROACH_TIMEOUT_TICKS) {
            // Could not reach this water, scan again from current position.
            ChatPilotClient.BARITONE.hardReset();
            waterTarget = null;
            enterStage(Stage.SCAN_WATER);
            return;
        }
        if (!ChatPilotClient.BARITONE.isPathing() && !ChatPilotClient.BARITONE.isActive()) {
            // FIX: Match the tighter target radius here too
            ChatPilotClient.BARITONE.gotoNear(waterTarget, 2);
        }
    }

    private void tickEquipRod(MinecraftClient mc) {
        rodSelectedSlot = equipFishingRod(mc);
        if (rodSelectedSlot < 0) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] No fishing rod in inventory; ending task");
            enterStage(Stage.DONE);
            return;
        }
        enterStage(Stage.AIM_AND_CAST);
    }

    private void tickAimAndCast(MinecraftClient mc) {
        if (waterTarget == null) { enterStage(Stage.SCAN_WATER); return; }
        // Make sure the rod is still selected (combat resume might have changed it).
        if (mc.player.getInventory().selectedSlot != rodSelectedSlot) {
            mc.player.getInventory().setSelectedSlot(rodSelectedSlot);
        }
        // Aim at the water surface.
        aimAt(mc, Vec3d.ofCenter(waterTarget));
        // Cast.
        var result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (result.isAccepted()) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Casted (catches so far: {})", catches);
            waitingStartTick = clientTick();
            enterStage(Stage.WAITING);
        } else if (ticksInStage() > 20 * 5) {
            // Cast keeps failing, possibly out of position. Reseat.
            ChatPilotMod.LOGGER.warn("[ChatPilot][Fishing] Cast not accepted, rescanning water");
            waterTarget = null;
            enterStage(Stage.SCAN_WATER);
        }
    }

    private void tickWaiting(MinecraftClient mc) {
        // Don't try to detect bites in the first tick or two: the bobber is
        // still in flight from the cast and its velocity is large in any
        // direction. CAST_GUARD_TICKS is enough for the bobber to settle.
        long elapsed = clientTick() - waitingStartTick;
        FishingBobberEntity bobber = mc.player.fishHook;

        if (bobber == null) {
            // Bobber gone (maybe despawned, or we never actually cast). Recast.
            if (elapsed > CAST_GUARD_TICKS) {
                ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Bobber missing after cast, retrying");
                enterStage(Stage.AIM_AND_CAST);
            }
            return;
        }
        if (elapsed < CAST_GUARD_TICKS) return;

        double vy = bobber.getVelocity().y;
        if (vy < ChatPilotClient.CONFIG.fishingBiteVelocityY) {
            // Bobber dipped: fish bit. Reel immediately.
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Bite detected (vy={})", vy);
            enterStage(Stage.REELING);
            return;
        }
        if (elapsed > ChatPilotClient.CONFIG.fishingMaxWaitTicks) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Wait timeout, recasting");
            enterStage(Stage.REELING); // reel current line, then cast fresh
        }
    }

    private void tickReeling(MinecraftClient mc) {
        // THE GHOST FIX: If the game or server already deleted the bobber entity,
        // the client pointer is null. Staying here causes a soft-lock loop.
        if (mc.player.fishHook == null) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Fishing] Bobber went missing in REELING! Forcing state reset.");

            // Clear any bad tracking states and cycle completely back to scanning
            waterTarget = null;
            enterStage(Stage.SCAN_WATER);
            return;
        }

        // Ensure the rod is still our active selection before clicking
        if (mc.player.getInventory().selectedSlot != rodSelectedSlot && rodSelectedSlot >= 0) {
            mc.player.getInventory().setSelectedSlot(rodSelectedSlot);
        }

        // Executing the actual physical reel-in click right here:
        mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);

        // Count this only as a catch if a bite was registered before the timeout.
        long elapsed = clientTick() - waitingStartTick;
        if (elapsed < ChatPilotClient.CONFIG.fishingMaxWaitTicks) {
            catches++;
        }

        if (catches >= ChatPilotClient.CONFIG.fishingCatchTarget) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Catch target met ({}), ending task", catches);
            enterStage(Stage.DONE);
            return;
        }

        // Advance to SETTLE to give the server a quick moment to deliver the item
        enterStage(Stage.SETTLE);
    }

    private void tickSettle() {
        if (ticksInStage() >= ChatPilotClient.CONFIG.fishingSettleTicks) {
            // Re-aim and cast again. Same waterTarget, same rod.
            enterStage(Stage.AIM_AND_CAST);
        }
    }

    private void tickExploreOutward() {
        if (ticksInStage() > EXPLORE_DURATION_TICKS) {
            enterStage(Stage.SCAN_WATER);
        }
    }

    /* ---------- helpers ---------- */

    private void enterStage(Stage next) {
        stage = next;
        stageStartTick = clientTick();
    }

    /**
     * Scans for the closest water surface block (still water with an air block
     * directly above) within the configured scan radius. Returns null if none
     * is in range.
     */
    private BlockPos findWaterSurface(MinecraftClient mc) {
        BlockPos playerPos = mc.player.getBlockPos();
        int r = 16; // Search radius

        for (int y = r; y >= -r; y--) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos p = playerPos.add(x, y, z);

                    // 1. Check if the block is a still fluid (water)
                    if (mc.world.getFluidState(p).isStill()) {

                        // 2. THE FIX: Ensure the block directly above it has an open line of sight to the sky
                        // This filters out underground caves, dark spaces, and subterranean lava/water traps.
                        if (mc.world.isSkyVisible(p.up())) {
                            return p; // Found a safe surface water source!
                        }
                    }
                }
            }
        }
        return null; // No open sky water found in range
    }

    /**
     * Find a fishing rod and ensure it's the selected hotbar item. Returns
     * the resulting hotbar slot index, or -1 if the inventory has no rod.
     *
     * Hotbar first (cheapest path: just setSelectedSlot). If the rod is
     * deeper in inventory, send a SWAP click to move it to hotbar slot 8 so
     * we don't disturb the player's preferred layout for slots 0-7.
     */
    private int equipFishingRod(MinecraftClient mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() == Items.FISHING_ROD) {
                inv.setSelectedSlot(i);
                return i;
            }
        }
        // Not in hotbar; search main inventory and swap into slot 8.
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.FISHING_ROD) {
                try {
                    int syncId = mc.player.playerScreenHandler.syncId;
                    // SWAP with hotbar slot 8 (button arg = 0..8 hotbar index).
                    mc.interactionManager.clickSlot(syncId, i, 8,
                        SlotActionType.SWAP, mc.player);
                    inv.setSelectedSlot(8);
                    return 8;
                } catch (Throwable t) {
                    ChatPilotMod.LOGGER.warn("[ChatPilot][Fishing] rod swap failed", t);
                    return -1;
                }
            }
        }
        return -1;
    }

    /** Set yaw/pitch so the player's gaze points at {@code target}. */
    private void aimAt(MinecraftClient mc, Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double horiz = Math.sqrt(dx*dx + dz*dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    @Override
    public boolean onStuck() {
        ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Stall in {}", stage);
        ChatPilotClient.BARITONE.hardReset();
        switch (stage) {
            case WALK_TO_WATER -> {
                if (waterTarget != null) {
                    ChatPilotClient.BARITONE.gotoNear(waterTarget, 3);
                    return true;
                }
                enterStage(Stage.SCAN_WATER);
                return true;
            }
            case EXPLORE_OUTWARD -> {
                ChatPilotClient.BARITONE.run("explore");
                return true;
            }
            case SCAN_WATER -> {
                // No water and stuck while scanning: try explore.
                ChatPilotClient.BARITONE.run("explore");
                enterStage(Stage.EXPLORE_OUTWARD);
                return true;
            }
            // While actively fishing the bot is standing still, so the
            // standard movement watchdog will fire constantly. We don't
            // recover those — let the watchdog count them as no-ops.
            case AIM_AND_CAST, WAITING, REELING, SETTLE, EQUIP_ROD -> {
                return false;
            }
            case DONE -> { return false; }
        }
        return false;
    }

    @Override
    public void onCombatStart() {
        savedForCombat = true;
        ChatPilotClient.BARITONE.stop();
        // If the bobber is in the water during combat we just leave it; reeling
        // is an interaction packet and combat handlers don't need it. The line
        // will despawn or be recast on resume.
    }

    @Override
    public void onCombatEnd() {
        if (!savedForCombat) return;
        savedForCombat = false;
        // Wherever we were, fall back to scanning fresh: combat may have moved
        // us out of casting range and the bobber is probably gone anyway.
        var mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            // Make sure the rod is still our selected slot.
            if (rodSelectedSlot >= 0
                && mc.player.getInventory().selectedSlot != rodSelectedSlot) {
                mc.player.getInventory().setSelectedSlot(rodSelectedSlot);
            }
        }
        waterTarget = null;
        enterStage(Stage.SCAN_WATER);
    }

    @Override
    public void cancel() {
        ChatPilotClient.BARITONE.hardReset();
        // Try to reel the bobber if one is still out so we don't leave a
        // dangling line on cancel.
        try {
            var mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null && mc.player.fishHook != null
                && rodSelectedSlot >= 0
                && mc.player.getInventory().getStack(rodSelectedSlot).getItem() == Items.FISHING_ROD) {
                if (mc.player.getInventory().selectedSlot != rodSelectedSlot) {
                    mc.player.getInventory().setSelectedSlot(rodSelectedSlot);
                }
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            }
        } catch (Throwable ignored) {}
    }

    private int ticksInStage() { return clientTick() - stageStartTick; }
    private static int clientTick() {
        return (int) (com.grammacrackers.chatpilot.event.TickClock.now() & 0x7fffffff);
    }
}
