package com.grammacrackers.chatpilot.movement;

import com.grammacrackers.chatpilot.ChatPilotClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Drives the camera the way a real player would.
 *
 * Baritone's freeLook is handed to this manager whenever Baritone is active
 * (walking OR mining) so Baritone never hard-snaps the view. This manager then
 * turns the head smoothly toward where the bot is going - along the walking
 * path, or toward whatever it is mining.
 */
public class LookWhereWalkingManager {

    /** Fraction of the remaining yaw/pitch error consumed per tick. */
    private static final float YAW_FOLLOW = 0.40f;
    private static final float PITCH_FOLLOW = 0.30f;

    /** Peak turn speed in degrees per tick. */
    private static final float MAX_YAW_SPEED = 18.0f;
    private static final float MAX_PITCH_SPEED = 6.0f;

    /** Below this yaw error the view is treated as already on-target. */
    private static final float SNAP_DEGREES = 0.1f;

    public void tick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) {
            return;
        }

        boolean enabled = ChatPilotClient.CONFIG != null && ChatPilotClient.CONFIG.lookWhereWalking;

        boolean baritoneActive = ChatPilotClient.BARITONE != null && ChatPilotClient.BARITONE.isActive();
        boolean mining = ChatPilotClient.BARITONE != null && ChatPilotClient.BARITONE.isMining();

        // Hand the camera to this smooth controller whenever Baritone is active
        // - walking OR mining - so Baritone never hard-snaps the view.
        if (ChatPilotClient.BARITONE != null) {
            ChatPilotClient.BARITONE.setFreeLook(enabled && baritoneActive);
        }

        if (!enabled) {
            return;
        }

        ClientPlayerEntity p = mc.player;

        if (shouldSkip(mc, p)) {
            return;
        }

        if (!baritoneActive) {
            return;
        }

        if (mining) {
            tickMiningCamera(p);
        } else {
            tickWalkingCamera(p);
        }
    }

    /**
     * Mining: smoothly aim at the point a short way ahead on Baritone's mining
     * path - in full 3D, so it looks down when digging down and along the
     * tunnel when branch-mining. When there is no path (breaking a block in
     * place) the view is simply held. freeLook keeps Baritone from snapping it.
     */
    private void tickMiningCamera(ClientPlayerEntity p) {
        BlockPos target = ChatPilotClient.BARITONE.getPathLookaheadTarget(
                ChatPilotClient.CONFIG.lookWhereWalkingPathLookaheadBlocks
        );

        if (target == null) {
            return;
        }

        Vec3d eye = p.getEyePos();
        Vec3d tc = Vec3d.ofCenter(target);

        double dx = tc.x - eye.x;
        double dy = tc.y - eye.y;
        double dz = tc.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        // Target basically on top of the bot - no stable bearing, hold.
        if (horiz < 1.0 && Math.abs(dy) < 1.0) {
            return;
        }

        if (targetBehindMovement(p, dx, dz)) {
            return;
        }

        // Yaw: only track it when there is enough horizontal offset for a
        // stable bearing (digging straight down has none - keep the yaw).
        if (horiz >= 1.0) {
            float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float yawDiff = wrapDegrees(desiredYaw - p.getYaw());

            float newYaw;
            if (Math.abs(yawDiff) <= SNAP_DEGREES) {
                newYaw = wrapDegrees(desiredYaw);
            } else {
                float step = clamp(yawDiff * YAW_FOLLOW, -MAX_YAW_SPEED, MAX_YAW_SPEED);
                newYaw = wrapDegrees(p.getYaw() + step);
            }

            p.setYaw(newYaw);
            p.setHeadYaw(newYaw);
        }

        float desiredPitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.max(horiz, 0.1))));
        desiredPitch = clamp(desiredPitch, -25.0f, 80.0f);
        float pitchStep = clamp((desiredPitch - p.getPitch()) * PITCH_FOLLOW,
                -MAX_PITCH_SPEED, MAX_PITCH_SPEED);
        p.setPitch(p.getPitch() + pitchStep);
    }

    /** Walking: smoothly track a point a short distance ahead on the path. */
    private void tickWalkingCamera(ClientPlayerEntity p) {
        BlockPos target = ChatPilotClient.BARITONE.getPathLookaheadTarget(
                ChatPilotClient.CONFIG.lookWhereWalkingPathLookaheadBlocks
        );

        if (target == null) {
            return;
        }

        Vec3d eye = p.getEyePos();
        Vec3d targetCenter = Vec3d.ofCenter(target);

        double dx = targetCenter.x - eye.x;
        double dz = targetCenter.z - eye.z;
        double horizontalDistSq = dx * dx + dz * dz;

        double minDist = Math.max(2.0, ChatPilotClient.CONFIG.lookWhereWalkingGoalMinDistance);

        if (horizontalDistSq < minDist * minDist) {
            return;
        }

        if (targetBehindMovement(p, dx, dz)) {
            return;
        }

        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDiff = wrapDegrees(desiredYaw - p.getYaw());

        float newYaw;
        if (Math.abs(yawDiff) <= SNAP_DEGREES) {
            newYaw = wrapDegrees(desiredYaw);
        } else {
            float step = clamp(yawDiff * YAW_FOLLOW, -MAX_YAW_SPEED, MAX_YAW_SPEED);
            newYaw = wrapDegrees(p.getYaw() + step);
        }

        float targetPitch = (float) ChatPilotClient.CONFIG.lookWhereWalkingPitch;
        float pitchStep = clamp((targetPitch - p.getPitch()) * PITCH_FOLLOW,
                -MAX_PITCH_SPEED, MAX_PITCH_SPEED);
        float newPitch = p.getPitch() + pitchStep;

        p.setYaw(newYaw);
        p.setHeadYaw(newYaw);
        p.setPitch(newPitch);
    }

    /**
     * True if the look-at target is behind the bot's actual direction of
     * travel. Used to stop the camera whipping around to look backward (e.g.
     * toward home) when a stale or odd path target gets computed.
     */
    private static boolean targetBehindMovement(ClientPlayerEntity p, double dx, double dz) {
        Vec3d vel = p.getVelocity();
        double velSq = vel.x * vel.x + vel.z * vel.z;

        if (velSq < 0.01) {
            return false;   // not moving meaningfully - aim normally
        }

        return (dx * vel.x + dz * vel.z) < 0.0;
    }

    private boolean shouldSkip(MinecraftClient mc, ClientPlayerEntity p) {
        if (mc.currentScreen != null) return true;
        if (p.hasVehicle()) return true;
        if (p.isUsingItem()) return true;

        return ChatPilotClient.COMBAT != null && ChatPilotClient.COMBAT.isInCombat();
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
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
