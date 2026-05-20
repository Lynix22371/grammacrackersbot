package com.grammacrackers.chatpilot.baritone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Watches the player position and Baritone activity. If Baritone reports active
 * but the player has not moved in N ticks (and is not breaking a block in front
 * of them), we declare a stall and let the TaskManager intervene.
 */
public class StuckDetector {

    private Vec3d lastPos = null;
    private int   stillTicks = 0;
    private long  lastResetTick = 0;

    public void reset() {
        lastPos = null;
        stillTicks = 0;
    }

    /** Returns true if a stall has just been detected this tick. */
    public boolean tick(int thresholdTicks, boolean expectingProgress) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity p = mc.player;
        if (p == null) { reset(); return false; }

        Vec3d cur = p.getPos();
        if (lastPos == null) {
            lastPos = cur;
            stillTicks = 0;
            return false;
        }

        double dx = cur.x - lastPos.x;
        double dy = cur.y - lastPos.y;
        double dz = cur.z - lastPos.z;
        double moved2 = dx*dx + dy*dy + dz*dz;

        if (moved2 < 0.0004) {            // < 0.02 blocks per tick
            if (expectingProgress) stillTicks++;
        } else {
            stillTicks = 0;
            lastPos = cur;
        }

        if (stillTicks >= thresholdTicks) {
            stillTicks = 0;        // arm the next window
            lastResetTick = p.getWorld().getTime();
            return true;
        }
        return false;
    }
}
