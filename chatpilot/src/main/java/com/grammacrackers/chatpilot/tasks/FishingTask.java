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
 * Fishing task. v1.3.2 replacement for the old wood-gathering vote slot.
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
 *
 * v1.3.2 Updates:
 *   1. Buoyancy, keep the boat afloat while fishing.
 *   2. If we're already floating in the water, just fish there.
 *   3. Look for a 3x3 source block water pool with sky blocks above it to
 *      fish for rarer items.
 *   4. Flowing water flexibility, allow it to fish in areas with flowing water.
 *   5. Bobber Despawn Reset. If the bobber disappears into thin air during the
 *      reeling phase, it logs a warning and forces a state reset back to SCAN_WATER.
 *   6. Optional Diagnostic Telemetry to report the 2D distance to the water target.
 *   7. No accidental fishing in Lava.
 *   8. Changed the distance to be closer to a water source to ensure what we catch, we keep.
 *   9. Attempted to make finding water sources that are closer. Still needs work.
 *   10. Gave the casting more arc to clear obstruction blocks that may prevent hitting water.
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

    private static final int MAX_EXPLORE_CYCLES         = 3;
    private static final int EXPLORE_DURATION_TICKS     = 18 * 20;
    private static final int APPROACH_TIMEOUT_TICKS     = 25 * 20;
    private static final int ARRIVAL_DIST_SQ            = 16;
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

        // DIAGNOSTIC START LOG
        ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] ==============================================");
        ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] TASK STARTED! Bot current position: [X: {}, Y: {}, Z: {}]",
                mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
        ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] ==============================================");

        enterStage(Stage.SCAN_WATER);
    }

    @Override
    public boolean tick() {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return false;

        if (mc.player.isTouchingWater() || mc.player.isInSwimmingPose()) {
            mc.options.jumpKey.setPressed(true);
        } else {
            if (!ChatPilotClient.BARITONE.isPathing()) {
                mc.options.jumpKey.setPressed(false);
            }
        }

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
        // 1. TARGET LOCK: If we already have a target and are moving towards it, DO NOT scan again!
        if (waterTarget != null) {
            enterStage(Stage.WALK_TO_WATER);
            return;
        }

        var currentFluid = mc.world.getFluidState(mc.player.getBlockPos());
        if (mc.player.isTouchingWater() && currentFluid.isStill() && (currentFluid.isOf(Fluids.WATER) || currentFluid.isOf(Fluids.FLOWING_WATER))) {
            var lookVec = mc.player.getRotationVec(1.0f);
            BlockPos forwardWater = mc.player.getBlockPos().add(
                    (int)(lookVec.x * 4),
                    0,
                    (int)(lookVec.z * 4)
            );

            waterTarget = forwardWater;
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Deep Ocean detected! Fishing in-place at surface.");
            enterStage(Stage.EQUIP_ROD);
            return;
        }

        ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Searching for valid 3x3 water pool...");
        BlockPos surface = findWaterSurface(mc);

        if (surface != null) {
            waterTarget = surface;
            ChatPilotClient.BARITONE.hardReset();

            // Tell Baritone to go to a fixed radius of 3 blocks from the target
            ChatPilotClient.BARITONE.gotoNear(surface, 3);
            enterStage(Stage.WALK_TO_WATER);
            return;
        }

        if (exploreCycles >= MAX_EXPLORE_CYCLES) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Could not find water after {} explores, ending", exploreCycles);
            enterStage(Stage.DONE);
            return;
        }
        exploreCycles++;
        ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] No water nearby, exploring outward (cycle {}/{})", exploreCycles, MAX_EXPLORE_CYCLES);
        ChatPilotClient.BARITONE.hardReset();
        ChatPilotClient.BARITONE.run("explore");
        enterStage(Stage.EXPLORE_OUTWARD);
    }

    private void tickWalkToWater(MinecraftClient mc) {
        if (waterTarget == null) { enterStage(Stage.SCAN_WATER); return; }

        if (mc.player.isTouchingWater() || mc.player.getBlockPos().getSquaredDistance(waterTarget) < 4) {
            ChatPilotClient.BARITONE.stop();
            ChatPilotClient.BARITONE.hardReset();
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] In the water or at the shoreline! Killing Baritone path and fishing here.");
            enterStage(Stage.EQUIP_ROD);
            return;
        }

        // 1. Calculate TRUE 3D distance so vertical elevation cliffs don't trick the bot
        double dx = waterTarget.getX() + 0.5 - mc.player.getX();
        double dy = waterTarget.getY() + 0.5 - mc.player.getY();
        double dz = waterTarget.getZ() + 0.5 - mc.player.getZ();
        double dist3D = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));

        // DIAGNOSTIC STATUS LOG (Reporting absolute 3D distance every 2 seconds)
        if (ticksInStage() % 40 == 0) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] En route to target [X: {}, Y: {}, Z: {}]. 3D Distance remaining: {} blocks.",
                    waterTarget.getX(), waterTarget.getY(), waterTarget.getZ(), Math.round(dist3D * 10.0) / 10.0);
        }

        // 2. Stop when within an ideal casting distance (between 2 and 5 blocks away in 3D space)
        if (dist3D <= 5.5 && dist3D >= 2.0) {
            ChatPilotClient.BARITONE.stop();
            ChatPilotClient.BARITONE.hardReset();
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Solid shoreline position reached. Distance: {} blocks. Stopping to fish.", Math.round(dist3D * 10.0) / 10.0);
            enterStage(Stage.EQUIP_ROD);
            return;
        }

        // 3. Safety fallback: If the bot overshoots or falls in, stop and try to fish anyway
        if (dist3D < 2.0) {
            ChatPilotClient.BARITONE.stop();
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.EQUIP_ROD);
            return;
        }

        if (ticksInStage() > APPROACH_TIMEOUT_TICKS) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Fishing] Walk timeout reached. Resetting target.");
            ChatPilotClient.BARITONE.hardReset();
            waterTarget = null;
            enterStage(Stage.SCAN_WATER);
            return;
        }

        // 4. Keep the target arrival radius locked at a consistent 3 blocks to match scan phase
        if (!ChatPilotClient.BARITONE.isPathing() && !ChatPilotClient.BARITONE.isActive()) {
            ChatPilotClient.BARITONE.gotoNear(waterTarget, 3);
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
        if (mc.player.getInventory().selectedSlot != rodSelectedSlot) {
            mc.player.getInventory().setSelectedSlot(rodSelectedSlot);
        }

        // FIX: Lift the target vector up by 1.5 blocks!
        // Instead of aiming at the water's surface, this forces the bot to look slightly upward,
        // throwing the bobber in a clean arc OVER the 1-block-high shoreline ridge.
        Vec3d highTarget = Vec3d.ofCenter(waterTarget).add(0, 1.5, 0);
        aimAt(mc, highTarget);

        var result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (result.isAccepted()) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Casted over obstruction (catches so far: {})", catches);
            waitingStartTick = clientTick();
            enterStage(Stage.WAITING);
        } else if (ticksInStage() > 20 * 5) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Fishing] Cast not accepted, rescanning water");
            waterTarget = null;
            enterStage(Stage.SCAN_WATER);
        }
    }

    private void tickWaiting(MinecraftClient mc) {
        long elapsed = clientTick() - waitingStartTick;
        FishingBobberEntity bobber = mc.player.fishHook;

        if (bobber == null) {
            if (elapsed > CAST_GUARD_TICKS) {
                ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Bobber missing after cast, retrying");
                enterStage(Stage.AIM_AND_CAST);
            }
            return;
        }
        if (elapsed < CAST_GUARD_TICKS) return;

        double vy = bobber.getVelocity().y;
        if (vy < ChatPilotClient.CONFIG.fishingBiteVelocityY) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Bite detected (vy={})", vy);
            enterStage(Stage.REELING);
            return;
        }
        if (elapsed > ChatPilotClient.CONFIG.fishingMaxWaitTicks) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Wait timeout, recasting");
            enterStage(Stage.REELING);
        }
    }

    private void tickReeling(MinecraftClient mc) {
        if (mc.player.fishHook == null) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Fishing] Bobber went missing in REELING! Forcing state reset.");
            waterTarget = null;
            enterStage(Stage.SCAN_WATER);
            return;
        }

        if (mc.player.getInventory().selectedSlot != rodSelectedSlot && rodSelectedSlot >= 0) {
            mc.player.getInventory().setSelectedSlot(rodSelectedSlot);
        }

        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        long elapsed = clientTick() - waitingStartTick;
        if (elapsed < ChatPilotClient.CONFIG.fishingMaxWaitTicks) {
            catches++;
        }

        if (catches >= ChatPilotClient.CONFIG.fishingCatchTarget) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Catch target met ({}), ending task", catches);
            enterStage(Stage.DONE);
            return;
        }

        enterStage(Stage.SETTLE);
    }

    private void tickSettle() {
        if (ticksInStage() >= ChatPilotClient.CONFIG.fishingSettleTicks) {
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

    private BlockPos findWaterSurface(MinecraftClient mc) {
        BlockPos playerPos = mc.player.getBlockPos();
        int horizontalRadius = 36;
        int verticalRadius = 12;

        java.util.List<BlockPos> validWaterSpots = new java.util.ArrayList<>();

        // 1. Gather ALL water blocks within the search box
        for (int y = -verticalRadius; y <= verticalRadius; y++) {
            for (int x = -horizontalRadius; x <= horizontalRadius; x++) {
                for (int z = -horizontalRadius; z <= horizontalRadius; z++) {
                    BlockPos p = playerPos.add(x, y, z);

                    var fluidState = mc.world.getFluidState(p);
                    if (!fluidState.isEmpty() && (fluidState.isOf(Fluids.WATER) || fluidState.isOf(Fluids.FLOWING_WATER))) {
                        // Drop it into the list for sorting
                        validWaterSpots.add(p);
                    }
                }
            }
        }

        // 2. SORT THE LIST: Absolute closest 3D straight-line distance comes first!
        validWaterSpots.sort((a, b) -> {
            double distA = a.getSquaredDistance(mc.player.getPos());
            double distB = b.getSquaredDistance(mc.player.getPos());
            return Double.compare(distA, distB);
        });

        // 3. Pick the absolute first block in the sorted list that passes the 3x3 open air check
        for (BlockPos p : validWaterSpots) {
            if (isCenterOf3x3OpenWater(mc, p)) {
                double actualDistance = Math.sqrt(p.getSquaredDistance(mc.player.getPos()));

                ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] >>> TARGET LOCKED! Absolute closest 3x3 water hole found at [X: {}, Y: {}, Z: {}] ({} blocks away). <<<",
                        p.getX(), p.getY(), p.getZ(), Math.round(actualDistance * 10.0) / 10.0);

                return p; // Return instantly! This guarantees it's the closest one.
            }
        }

        ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Scan completed: No valid 3x3 water found within search bounds.");
        return null;
    }

    private boolean isCenterOf3x3OpenWater(MinecraftClient mc, BlockPos center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos checkPos = center.add(dx, 0, dz);

                var state = mc.world.getFluidState(checkPos);
                // Must be water or flowing water
                if (state.isEmpty() || (!state.isOf(Fluids.WATER) && !state.isOf(Fluids.FLOWING_WATER))) {
                    return false;
                }

                // CHANGED: Instead of strict sky visibility, just ensure there is physical room to cast
                // This stops leaves, trees, and bridges from making the bot blind to nearby water.
                if (!mc.world.getBlockState(checkPos.up()).isAir() && !mc.world.getFluidState(checkPos.up()).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private int equipFishingRod(MinecraftClient mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() == Items.FISHING_ROD) {
                inv.setSelectedSlot(i);
                return i;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.FISHING_ROD) {
                try {
                    int syncId = mc.player.playerScreenHandler.syncId;
                    mc.interactionManager.clickSlot(syncId, i, 8, SlotActionType.SWAP, mc.player);
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
                    ChatPilotClient.BARITONE.gotoNear(waterTarget, 2);
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
                ChatPilotClient.BARITONE.run("explore");
                enterStage(Stage.EXPLORE_OUTWARD);
                return true;
            }
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
    }

    @Override
    public void onCombatEnd() {
        if (!savedForCombat) return;
        savedForCombat = false;
        var mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            if (rodSelectedSlot >= 0 && mc.player.getInventory().selectedSlot != rodSelectedSlot) {
                mc.player.getInventory().setSelectedSlot(rodSelectedSlot);
            }
        }
        waterTarget = null;
        enterStage(Stage.SCAN_WATER);
    }

    @Override
    public void cancel() {
        ChatPilotClient.BARITONE.hardReset();
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
