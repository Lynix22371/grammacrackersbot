package com.grammacrackers.chatpilot.movement;

import com.grammacrackers.chatpilot.ChatPilotClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

public class LookWhereWalkingManager {
    private Vec3d lastPos = null;

    private double avgDx = 0.0;
    private double avgDz = 0.0;

    private Float lockedYaw = null;
    private int lockTicks = 0;
    private int movingTicks = 0;

    public void tick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) {
            resetTracking();
            return;
        }

        if (ChatPilotClient.CONFIG == null || !ChatPilotClient.CONFIG.lookWhereWalking) {
            resetTracking();
            return;
        }

        ClientPlayerEntity p = mc.player;

        // Do not fight other systems that intentionally control camera/movement.
        if (mc.currentScreen != null) {
            resetTracking();
            return;
        }

        if (p.hasVehicle()) {
            resetTracking();
            return;
        }

        if (ChatPilotClient.COMBAT != null && ChatPilotClient.COMBAT.isInCombat()) {
            resetTracking();
            return;
        }

        if (ChatPilotClient.BARITONE != null && ChatPilotClient.BARITONE.isMining()) {
            resetTracking();
            return;
        }

        if (p.isUsingItem()) {
            resetTracking();
            return;
        }

        if (p.handSwinging) {
            return;
        }

        boolean baritoneWalking =
                ChatPilotClient.BARITONE != null
                        && ChatPilotClient.BARITONE.isActive()
                        && !ChatPilotClient.BARITONE.isMining();

        if (!baritoneWalking) {
            resetTracking();
            lastPos = p.getPos();
            return;
        }

        Vec3d now = p.getPos();

        if (lastPos == null) {
            lastPos = now;
            return;
        }

        Vec3d delta = now.subtract(lastPos);
        lastPos = now;

        double rawDx = delta.x;
        double rawDz = delta.z;
        double rawSpeedSq = rawDx * rawDx + rawDz * rawDz;

        double minSpeed = Math.max(0.001, ChatPilotClient.CONFIG.lookWhereWalkingMinSpeed);

        // Not really moving. Decay average and unlock heading.
        if (rawSpeedSq < minSpeed * minSpeed) {
            movingTicks = 0;
            lockTicks = 0;
            lockedYaw = null;
            avgDx *= 0.80;
            avgDz *= 0.80;
            return;
        }

        movingTicks++;

        // Very slow low-pass filter.
        // This is the important anti-shake part: ignore tiny Baritone zig-zags.
        avgDx = avgDx * 0.96 + rawDx * 0.04;
        avgDz = avgDz * 0.96 + rawDz * 0.04;

        // Wait until the average direction is stable.
        if (movingTicks < 20) {
            return;
        }

        double avgSpeedSq = avgDx * avgDx + avgDz * avgDz;

        if (avgSpeedSq < minSpeed * minSpeed) {
            return;
        }

        float measuredYaw = (float) (Math.toDegrees(Math.atan2(avgDz, avgDx)) - 90.0);

        if (lockedYaw == null) {
            lockedYaw = measuredYaw;
            lockTicks = 0;
        }

        lockTicks++;

        float diffFromLock = wrapDegrees(measuredYaw - lockedYaw);

        double relockAngle = Math.max(5.0, ChatPilotClient.CONFIG.lookWhereWalkingRelockAngle);
        int relockTicks = Math.max(1, ChatPilotClient.CONFIG.lookWhereWalkingRelockTicks);

        // Only accept a new walking direction after it has been different for a while.
        // This prevents the camera from following every tiny left/right correction.
        if (Math.abs(diffFromLock) > relockAngle && lockTicks > relockTicks) {
            lockedYaw = measuredYaw;
            lockTicks = 0;
        }

        float yawDiff = wrapDegrees(lockedYaw - p.getYaw());
        double deadzone = Math.max(0.0, ChatPilotClient.CONFIG.lookWhereWalkingYawDeadzone);

        // If the camera is already close enough, do nothing.
        if (Math.abs(yawDiff) < deadzone) {
            return;
        }

        float targetPitch = (float) ChatPilotClient.CONFIG.lookWhereWalkingPitch;

        // Do NOT force minimum 1.0 here. Smaller values like 0.4 or 0.8 are useful.
        float maxYawStep = (float) Math.max(0.05, ChatPilotClient.CONFIG.lookWhereWalkingMaxYawPerTick);
        float maxPitchStep = (float) Math.max(0.05, ChatPilotClient.CONFIG.lookWhereWalkingMaxPitchPerTick);

        float newYaw = approachAngle(p.getYaw(), lockedYaw, maxYawStep);
        float newPitch = approachAngle(p.getPitch(), targetPitch, maxPitchStep);

        p.setYaw(newYaw);
        p.setHeadYaw(newYaw);
        p.setPitch(newPitch);

        // Important: do not call p.setBodyYaw(newYaw).
        // Forcing body yaw every tick can create visible left/right wobble.
    }

    private void resetTracking() {
        lastPos = null;
        avgDx = 0.0;
        avgDz = 0.0;
        lockedYaw = null;
        lockTicks = 0;
        movingTicks = 0;
    }

    private static float approachAngle(float current, float target, float maxStep) {
        float diff = wrapDegrees(target - current);

        if (diff > maxStep) diff = maxStep;
        if (diff < -maxStep) diff = -maxStep;

        return current + diff;
    }

    private static float wrapDegrees(float deg) {
        deg %= 360.0f;

        if (deg >= 180.0f) {
            deg -= 360.0f;
        }

        if (deg < -180.0f) {
            deg += 360.0f;
        }

        return deg;
    }
}
