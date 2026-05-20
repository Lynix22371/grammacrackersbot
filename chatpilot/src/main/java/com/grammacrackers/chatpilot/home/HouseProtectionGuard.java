package com.grammacrackers.chatpilot.home;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Reactive guard that prevents Baritone from breaking the house.
 *
 * Strategy:
 *   1. Each tick, if a task is RUNNING and Baritone is mining/active, check
 *      whether the player is inside the protection sphere around the bed.
 *   2. If yes, force a hard reset on Baritone and queue a "step away" goal
 *      to a point exactly (radius + 6) blocks away from the bed in the
 *      player's current heading.
 *   3. The MiningTask state machine sees Baritone go idle and re-enters its
 *      MOVE_AWAY stage on its next tick, so the task itself recovers.
 *
 * This is purely defensive. The MiningTask already chooses an anchor at
 * miningMinDistanceFromHome (default 40), which is well beyond the protection
 * radius (default 25). The guard exists as belt-and-suspenders for cases
 * where pathing chooses a route that cuts through the house.
 */
public class HouseProtectionGuard {

    private long cooldownUntilTick = 0L;
    private long tickCounter = 0L;

    public void tick() {
        tickCounter++;
        if (tickCounter < cooldownUntilTick) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null) return;
        if (ChatPilotClient.HOME == null || !ChatPilotClient.HOME.hasHome()) return;
        if (ChatPilotClient.CONFIG == null) return;

        int radius = ChatPilotClient.CONFIG.houseProtectionRadius;
        BlockPos bed = ChatPilotClient.HOME.getBedPos();
        BlockPos here = mc.player.getBlockPos();

        // Only intervene while Baritone is actively breaking blocks. Walking
        // through the area is fine; only mining inside the protection zone
        // counts as griefing.
        if (!ChatPilotClient.BARITONE.isMining()) return;

        if (!withinSphere(here, bed, radius)) return;

        ChatPilotMod.LOGGER.warn("[ChatPilot] House guard tripped at {} (bed {}). Cancelling and stepping out.",
                                  here, bed);

        // Pick a safe target outside the protection sphere along the current heading.
        Vec3d facing = mc.player.getRotationVec(1.0F).normalize();
        if (facing.lengthSquared() < 0.01) facing = new Vec3d(1, 0, 0);

        int safeDist = radius + 8;
        int tx = bed.getX() + (int) Math.round(facing.x * safeDist);
        int tz = bed.getZ() + (int) Math.round(facing.z * safeDist);
        int ty = bed.getY();

        ChatPilotClient.BARITONE.hardReset();
        ChatPilotClient.BARITONE.gotoBlock(new BlockPos(tx, ty, tz));

        // Backoff so we do not spam hardResets if the player drifts back in
        cooldownUntilTick = tickCounter + 60; // 3 seconds
    }

    private static boolean withinSphere(BlockPos a, BlockPos b, int radius) {
        long dx = (long) a.getX() - b.getX();
        long dy = (long) a.getY() - b.getY();
        long dz = (long) a.getZ() - b.getZ();
        return (dx * dx + dy * dy + dz * dz) <= (long) radius * radius;
    }
}
