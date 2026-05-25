package com.grammacrackers.chatpilot.movement;

import com.grammacrackers.chatpilot.ChatPilotClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Drives the camera the way a real player would.
 *
 * Baritone's own camera control (freeLook) is handed off to this manager
 * whenever Baritone is active - walking OR mining - so Baritone never
 * hard-snaps the visible view to each block it targets. This manager then
 * turns the head smoothly:
 *   - Walking: track the path a short distance ahead.
 *   - Mining: settle to a calm, slightly downward view and hold it, instead of
 *     jerking the camera around to every block being broken.
 */
public class LookWhereWalkingManager {

    /** Fraction of the remaining yaw/pitch error consumed per tick. */
    private static final float YAW_FOLLOW = 0.40f;
    private static final float PITCH_FOLLOW = 0.30f;

    /** Peak turn speed in degrees per tick - the cap reached on sharp corners. */
    private static final float MAX_YAW_SPEED = 18.0f;
    private static final float MAX_PITCH_SPEED = 6.0f;

    /** Below this yaw error the view is treated as already on-target. */
    private static final float SNAP_DEGREES = 0.1f;

    /** Calm downward pitch eased to (and held) while mining. */
    private static final float MINING_PITCH = 45.0f;

    public void tick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) {
            return;
        }

        boolean enabled = ChatPilotClient.CONFIG != null && ChatPilotClient.CONFIG.lookWhereWalking;

        boolean baritoneActive = ChatPilotClient.BARITONE != null && ChatPilotClient.BARITONE.isActive();
        boolean mining = ChatPilotClient.BARITONE != null && ChatPilotClient.BARITONE.isMining();

        /*
         * Hand the camera to this smooth controller whenever Baritone is active
         * - walking OR mining. With freeLook = true Baritone keeps its
         * rotations internal (for raytracing) and never hard-snaps the visible
         * view, which is what made the camera jerk around while digging.
         */
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
     * Mining: ease to a calm, slightly downward view and hold it. The yaw is
     * left where it is - the goal is simply to STOP the rapid per-block
     * rotation, not to chase each block.
     */
    private void tickMiningCamera(ClientPlayerEntity p) {
        float pitchDiff = MINING_PITCH - p.getPitch();
        float pitchStep = clamp(pitchDiff * PITCH_FOLLOW, -MAX_PITCH_SPEED, MAX_PITCH_SPEED);
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

        // Too close to the look-ahead point to read a stable bearing - hold.
        if (horizontalDistSq < minDist * minDist) {
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
        float pitchDiff = targetPitch - p.getPitch();
        float pitchStep = clamp(pitchDiff * PITCH_FOLLOW, -MAX_PITCH_SPEED, MAX_PITCH_SPEED);
        float newPitch = p.getPitch() + pitchStep;

        p.setYaw(newYaw);
        p.setHeadYaw(newYaw);
        p.setPitch(newPitch);

        // Do NOT call p.setBodyYaw - the body keeps following Baritone's
        // movement direction; only the head/camera turns ahead.
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
