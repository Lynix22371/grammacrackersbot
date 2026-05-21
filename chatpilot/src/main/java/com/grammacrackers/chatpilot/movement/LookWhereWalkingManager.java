package com.grammacrackers.chatpilot.movement;

import com.grammacrackers.chatpilot.ChatPilotClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

public class LookWhereWalkingManager {
    private Vec3d lastPos = null;
    private int stillTicks = 0;

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

        double dx = delta.x;
        double dz = delta.z;
        double speedSq = dx * dx + dz * dz;

        // Ignore tiny movement jitter.
        double minSpeed = Math.max(0.001, ChatPilotClient.CONFIG.lookWhereWalkingMinSpeed);
        if (speedSq < minSpeed * minSpeed) {
            stillTicks++;
            return;
        }

        stillTicks = 0;

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float targetPitch = (float) ChatPilotClient.CONFIG.lookWhereWalkingPitch;

        float maxYawStep = (float) Math.max(1.0, ChatPilotClient.CONFIG.lookWhereWalkingMaxYawPerTick);
        float maxPitchStep = (float) Math.max(1.0, ChatPilotClient.CONFIG.lookWhereWalkingMaxPitchPerTick);

        float newYaw = approachAngle(p.getYaw(), targetYaw, maxYawStep);
        float newPitch = approachAngle(p.getPitch(), targetPitch, maxPitchStep);

        p.setYaw(newYaw);
        p.setHeadYaw(newYaw);
        p.setBodyYaw(newYaw);
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
