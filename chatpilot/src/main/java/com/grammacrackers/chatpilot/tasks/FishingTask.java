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
 * Fishing task. v1.4.0 replacement for the old wood-gathering vote slot.
 *
 * v1.4.0 Updates:
 * 1. Buoyancy: Keeps the player jumping to stay afloat while fishing or swimming.
 * 2. Deep Ocean Fallback: If already floating in water, skips pathfinding and fishes in-place 4 blocks ahead.
 * 3. Ultimate Underground Filter: Scans for a 3x3 water pool that strictly has open sky access directly above it.
 * 4. Fluid Flexibility: Allows scanning and targeting of both still and flowing water.
 * 5. Reeling Despawn Safety Net: Automatically resets state to SCAN_WATER if the server or an anti-lag plugin despawns the bobber during reeling.
 * 6. Hard-Capped Proximity Sort: Forces absolute 3D straight-line distance sorting to guarantee locking onto the closest valid water block first.
 * 7. Anti-Lava Safeguard: Restricts scanning parameters to ensure the bot never attempts to cast into lava.
 * 8. Tightened Shoreline Approach: Adjusted Baritone pathing bounds to stop closer to the target pool, ensuring catches aren't lost on the bank.
 * 9. Dynamic Lob Modifier: Lifts the pitching arc up by 4.5 degrees per block of horizontal distance to clear lips, fences, and 1-block-high shoreline ridges.
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

        double dx = waterTarget.getX() + 0.5 - mc.player.getX();
        double dy = waterTarget.getY() + 0.5 - mc.player.getY();
        double dz = waterTarget.getZ() + 0.5 - mc.player.getZ();
        double dist3D = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));

        if (ticksInStage() % 40 == 0) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] En route to target [X: {}, Y: {}, Z: {}]. 3D Distance remaining: {} blocks.",
                    waterTarget.getX(), waterTarget.getY(), waterTarget.getZ(), Math.round(dist3D * 10.0) / 10.0);
        }

        if (dist3D <= 3.0 && dist3D >= 1.5) {
            ChatPilotClient.BARITONE.stop();
            ChatPilotClient.BARITONE.hardReset();
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Perfect shoreline position reached. Distance: {} blocks. Stopping to fish.", Math.round(dist3D * 10.0) / 10.0);
            enterStage(Stage.EQUIP_ROD);
            return;
        }

        if (dist3D < 1.5) {
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

        if (!ChatPilotClient.BARITONE.isPathing() && !ChatPilotClient.BARITONE.isActive()) {
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
        if (mc.player.getInventory().selectedSlot != rodSelectedSlot) {
            mc.player.getInventory().setSelectedSlot(rodSelectedSlot);
        }

        Vec3d highTarget = Vec3d.ofCenter(waterTarget).add(0, 1.0, 0);
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

        for (int y = -verticalRadius; y <= verticalRadius; y++) {
            for (int x = -horizontalRadius; x <= horizontalRadius; x++) {
                for (int z = -horizontalRadius; z <= horizontalRadius; z++) {
                    BlockPos p = playerPos.add(x, y, z);

                    var fluidState = mc.world.getFluidState(p);
                    if (!fluidState.isEmpty() && (fluidState.isOf(Fluids.WATER) || fluidState.isOf(Fluids.FLOWING_WATER))) {
                        validWaterSpots.add(p);
                    }
                }
            }
        }

        validWaterSpots.sort((a, b) -> {
            double distA = a.getSquaredDistance(mc.player.getPos());
            double distB = b.getSquaredDistance(mc.player.getPos());
            return Double.compare(distA, distB);
        });

        for (BlockPos p : validWaterSpots) {
            if (isCenterOf3x3OpenWater(mc, p)) {
                double actualDistance = Math.sqrt(p.getSquaredDistance(mc.player.getPos()));

                ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] >>> TARGET LOCKED! Absolute closest 3x3 water hole found at [X: {}, Y: {}, Z: {}] ({} blocks away). <<<",
                        p.getX(), p.getY(), p.getZ(), Math.round(actualDistance * 10.0) / 10.0);

                return p;
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
                if (state.isEmpty() || (!state.isOf(Fluids.WATER) && !state.isOf(Fluids.FLOWING_WATER))) {
                    return false;
                }

                if (!mc.world.isSkyVisible(checkPos.up())) {
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

        float basePitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        float lobCompensation = (float) (horiz * 4.5);

        float finalPitch = basePitch - lobCompensation;
        if (finalPitch < -60.0f) finalPitch = -60.0f;

        mc.player.setYaw(yaw);
        mc.player.setPitch(finalPitch);
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
