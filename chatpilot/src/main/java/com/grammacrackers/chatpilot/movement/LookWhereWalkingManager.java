package com.grammacrackers.chatpilot.movement;

import com.grammacrackers.chatpilot.ChatPilotClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

public class LookWhereWalkingManager {
    private Vec3d lastPos = null;
    private int stillTicks = 0;
    private double avgDx = 0.0;
    private double avgDz = 0.0;
    private int movingTicks = 0;
 

    public void tick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) {
            lastPos = null;
            return;
        }

        if (ChatPilotClient.CONFIG == null || !ChatPilotClient.CONFIG.lookWhereWalking) {
            return;
        }

        ClientPlayerEntity p = mc.player;

        // Do not fight other systems that intentionally control the camera.
        if (mc.currentScreen != null) return;
        if (ChatPilotClient.COMBAT != null && ChatPilotClient.COMBAT.isInCombat()) return;
        if (ChatPilotClient.BARITONE != null && ChatPilotClient.BARITONE.isMining()) return;
        if (p.isUsingItem()) return;
        if (p.handSwinging) return;

        boolean baritoneWalking =
                ChatPilotClient.BARITONE != null
                        && ChatPilotClient.BARITONE.isActive()
                        && !ChatPilotClient.BARITONE.isMining();

        if (!baritoneWalking) {
            lastPos = p.getPos();
            stillTicks = 0;
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

        if (rawSpeedSq < minSpeed * minSpeed) {
            movingTicks = 0;
            avgDx *= 0.90;
            avgDz *= 0.90;
            return;
        }

        movingTicks++;

        avgDx = avgDx * 0.92 + rawDx * 0.08;
        avgDz = avgDz * 0.92 + rawDz * 0.08;

        if (movingTicks < 10) {
            return;
        }

        double dx = avgDx;
        double dz = avgDz;
        double speedSq = dx * dx + dz * dz;

        if (speedSq < minSpeed * minSpeed) {
            return;
        }
     

        stillTicks = 0;

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDiff = wrapDegrees(targetYaw - p.getYaw());

        // Ignore tiny direction changes. This stops left/right shaking.
        if (Math.abs(yawDiff) < ChatPilotClient.CONFIG.lookWhereWalkingYawDeadzone) {
            return;
        }
        float targetPitch = (float) ChatPilotClient.CONFIG.lookWhereWalkingPitch;

        float maxYawStep = (float) Math.max(1.0, ChatPilotClient.CONFIG.lookWhereWalkingMaxYawPerTick);
        float maxPitchStep = (float) Math.max(1.0, ChatPilotClient.CONFIG.lookWhereWalkingMaxPitchPerTick);

        float newYaw = approachAngle(p.getYaw(), targetYaw, maxYawStep);
        float newPitch = approachAngle(p.getPitch(), targetPitch, maxPitchStep);

        p.setYaw(newYaw);
        p.setHeadYaw(newYaw);
        p.setPitch(newPitch);
    }

    private static float approachAngle(float current, float target, float maxStep) {
        float diff = wrapDegrees(target - current);

        if (diff > maxStep) diff = maxStep;
        if (diff < -maxStep) diff = -maxStep;

        return current + diff;
    }

    private static float wrapDegrees(float deg) {
        deg %= 360.0f;
        if (deg >= 180.0f) deg -= 360.0f;
        if (deg < -180.0f) deg += 360.0f;
        return deg;
    }
}
