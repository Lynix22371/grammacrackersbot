package com.grammacrackers.chatpilot.home;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import com.grammacrackers.chatpilot.tasks.TaskManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Guards the area around the house.
 *
 * Two layers:
 *   1. No-dig zone (applyNoDigZone): while the RETURN-HOME flow is running,
 *      Baritone block-breaking is disabled within houseNoDigRadius of the bed.
 *      This stops the bot tunnelling or surfacing through the ground near home
 *      and leaving holes. It is scoped to the return-home flow ONLY - dedicated
 *      mining is never restricted, because forcing allowBreak off mid-mine just
 *      deadlocks Baritone ("Unable to mine when allowBreak is false").
 *   2. Step-out guard: if Baritone is actively mining inside the tighter
 *      houseProtectionRadius sphere, it is hard-reset and sent to a point
 *      just outside the sphere so it stops breaking house blocks.
 */
public class HouseProtectionGuard {

    private long cooldownUntilTick = 0L;
    private long tickCounter = 0L;

    public void tick() {
        tickCounter++;

        // No-dig zone enforcement runs every tick, independent of the
        // step-out cooldown below.
        applyNoDigZone();

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

    /**
     * allowBreak / allowPlace ownership.
     *
     * While the return-home / deposit flow runs, the ReturnHomeAndDepositTask
     * manages allowBreak and allowPlace itself - it knows when it is stuck
     * inside the no-dig ring and must be allowed to dig/place to free itself.
     * In every other phase (especially mining) breaking must be enabled,
     * otherwise Baritone's mine process deadlocks with "Unable to mine when
     * allowBreak is false", and placing must be enabled so Baritone can
     * pillar/bridge out of holes.
     */
    private void applyNoDigZone() {
        if (ChatPilotClient.BARITONE == null) return;

        boolean returningHome = false;

        if (ChatPilotClient.TASKS != null) {
            TaskManager.Phase phase = ChatPilotClient.TASKS.getPhase();
            returningHome = phase == TaskManager.Phase.RETURNING_HOME
                         || phase == TaskManager.Phase.DEPOSITING;
        }

        // Outside the return-home flow, always allow breaking and placing so
        // mining and pillar-escape work. During return-home, leave both to
        // the task itself.
        if (!returningHome) {
            ChatPilotClient.BARITONE.setAllowBreak(true);
            ChatPilotClient.BARITONE.setAllowPlace(true);
        }
    }

    private static boolean withinSphere(BlockPos a, BlockPos b, int radius) {
        long dx = (long) a.getX() - b.getX();
        long dy = (long) a.getY() - b.getY();
        long dz = (long) a.getZ() - b.getZ();
        return (dx * dx + dy * dy + dz * dz) <= (long) radius * radius;
    }
}
