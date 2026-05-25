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
 * Fishing task. v1.2.0 - Diagnostic Logging & Strict 3x3 Pool Selection.
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

            // Fix: Tell Baritone to go NEAR the target block, but don't force it to submerge
            ChatPilotClient.BARITONE.gotoNear(surface, 3);

            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] TARGET LOCK! Going to water coordinate: [X: {}, Y: {}, Z: {}]",
                    surface.getX(), surface.getY(), surface.getZ());

            enterStage(Stage.WALK_TO_WATER);
            return;
        }
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

        double dx = waterTarget.getX() + 0.5 - mc.player.getX();
        double dz = waterTarget.getZ() + 0.5 - mc.player.getZ();
        double dist2D = (dx * dx) + (dz * dz);

        // EN ROUTE STATUS LOG (Every 2 seconds / 40 ticks)
        if (ticksInStage() % 40 == 0) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] En route to target [X: {}, Y: {}, Z: {}]. Distance remaining: {} blocks.",
                    waterTarget.getX(), waterTarget.getY(), waterTarget.getZ(), Math.round(Math.sqrt(dist2D) * 10.0) / 10.0);
        }

        if (dist2D <= 6.25) {
            ChatPilotClient.BARITONE.stop();
            ChatPilotClient.BARITONE.hardReset();
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Close enough to target water. Stopping to fish.");
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
        aimAt(mc, Vec3d.ofCenter(waterTarget));
        var result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (result.isAccepted()) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Fishing] Casted (catches so far: {})", catches);
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

        BlockPos closestWater = null;
        int minManhattanDistance = Integer.MAX_VALUE;

        for (int y = verticalRadius; y >= -verticalRadius; y--) {
            for (int x = -horizontalRadius; x <= horizontalRadius; x++) {
                for (int z = -horizontalRadius; z <= horizontalRadius; z++) {
                    BlockPos p = playerPos.add(x, y, z);

                    var fluidState = mc.world.getFluidState(p);
                    // MODIFIED: Accept both still and flowing water if it forms a full block
                    if (!fluidState.isEmpty() && (fluidState.isOf(Fluids.WATER) || fluidState.isOf(Fluids.FLOWING_WATER))) {
                        if (mc.world.isSkyVisible(p.up())) {
                            if (isCenterOf3x3OpenWater(mc, p)) {

                                int dx = Math.abs(p.getX() - playerPos.getX());
                                int dy = Math.abs(p.getY() - playerPos.getY());
                                int dz = Math.abs(p.getZ() - playerPos.getZ());
                                int manhattanDist = dx + (dy * 2) + dz;

                                if (manhattanDist < minManhattanDistance) {
                                    minManhattanDistance = manhattanDist;
                                    closestWater = p;
                                }
                            }
                        }
                    }
                }
            }
        }
        return closestWater;
    }

    private boolean isCenterOf3x3OpenWater(MinecraftClient mc, BlockPos center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos checkPos = center.add(dx, 0, dz);

                var state = mc.world.getFluidState(checkPos);
                // MODIFIED: Loop blocks can be flowing water mechanics as long as they aren't empty air/lava
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
