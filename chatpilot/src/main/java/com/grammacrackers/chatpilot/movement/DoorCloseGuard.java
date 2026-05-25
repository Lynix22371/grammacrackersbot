package com.grammacrackers.chatpilot.movement;

import com.grammacrackers.chatpilot.ChatPilotClient;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Opens doors and fence gates in the bot's way, and closes them again once the
 * bot has walked through.
 *
 * Without this the bot walks face-first into closed fence gates (which are not
 * {@link DoorBlock}s, so the old close-only guard ignored them) and never gets
 * past - and it also left a trail of open doors that let mobs into the house.
 *
 * Each tick:
 *   1. OPEN  - if the bot is travelling and a CLOSED door/fence gate is just
 *      ahead, right-click it open. This runs even when the bot is pressed
 *      motionless against the gate (e.g. the manual nether-portal approach),
 *      because it keys off the look direction, not movement.
 *   2. CLOSE - while the bot is moving, an OPEN door/fence gate a couple of
 *      blocks behind its direction of travel is right-clicked shut.
 *
 * Right-clicking a door/gate toggles it, so OPEN only ever targets closed ones
 * and CLOSE only ever targets open ones - they never fight over a barrier.
 */
public class DoorCloseGuard {

    /** How far around the bot to look for a barrier. */
    private static final int SCAN = 3;

    /** Squared horizontal range within which a barrier ahead is opened. */
    private static final double OPEN_RANGE_SQ = 3.0 * 3.0;

    private int cooldown = 0;

    public void tick(MinecraftClient mc) {
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (mc == null || mc.player == null || mc.world == null
                || mc.interactionManager == null || mc.options == null) {
            return;
        }

        // 1. Open a closed door/gate the bot is walking into.
        if (tickOpenAhead(mc)) {
            cooldown = 6;
            return;
        }

        // 2. Close an open door/gate behind the bot.
        tickCloseBehind(mc);
    }

    /** Opens the nearest closed door/fence gate ahead of a travelling bot. */
    private boolean tickOpenAhead(MinecraftClient mc) {
        Vec3d vel = mc.player.getVelocity();
        boolean moving = vel.x * vel.x + vel.z * vel.z > 0.0009;   // ~0.03 b/tick

        // Only open things when the bot is actually trying to go somewhere, so
        // it doesn't fiddle with the player's gates while standing idle.
        boolean travelling = moving
                || mc.options.forwardKey.isPressed()
                || (ChatPilotClient.BARITONE != null && ChatPilotClient.BARITONE.isPathing());
        if (!travelling) {
            return false;
        }

        // Forward = where the bot is trying to go. The look yaw points down the
        // path while walking and at the portal during the manual portal
        // approach, and stays valid even when the bot is pinned still.
        double yawRad = Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(yawRad);
        double fz =  Math.cos(yawRad);

        Vec3d pos = mc.player.getPos();
        BlockPos here = mc.player.getBlockPos();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -SCAN; dx <= SCAN; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -SCAN; dz <= SCAN; dz++) {
                    BlockPos p = here.add(dx, dy, dz);
                    BlockState st = mc.world.getBlockState(p);

                    if (!isOpenableBarrier(st)) continue;
                    if (st.get(Properties.OPEN)) continue;        // already open

                    Vec3d c = Vec3d.ofCenter(p);
                    double ddx = c.x - pos.x;
                    double ddz = c.z - pos.z;
                    double horiz2 = ddx * ddx + ddz * ddz;

                    if (horiz2 > OPEN_RANGE_SQ) continue;          // too far
                    if (ddx * fx + ddz * fz <= 0.0) continue;      // not ahead

                    if (horiz2 < bestDist) {
                        bestDist = horiz2;
                        best = p;
                    }
                }
            }
        }

        if (best == null) {
            return false;
        }

        toggle(mc, best);
        return true;
    }

    /** Closes an open door/fence gate behind a moving bot. */
    private void tickCloseBehind(MinecraftClient mc) {
        Vec3d vel = mc.player.getVelocity();

        // Only act while genuinely travelling, so we can tell "behind" from
        // "ahead" and don't close a door the bot is about to walk through.
        if (vel.x * vel.x + vel.z * vel.z < 0.0025) {
            return;
        }

        Vec3d pos = mc.player.getPos();
        BlockPos here = mc.player.getBlockPos();

        for (int dx = -SCAN; dx <= SCAN; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -SCAN; dz <= SCAN; dz++) {
                    BlockPos p = here.add(dx, dy, dz);
                    BlockState st = mc.world.getBlockState(p);

                    if (!isOpenableBarrier(st)) continue;
                    if (!st.get(Properties.OPEN)) continue;       // already closed

                    Vec3d c = Vec3d.ofCenter(p);
                    double ddx = c.x - pos.x;
                    double ddz = c.z - pos.z;
                    double horiz2 = ddx * ddx + ddz * ddz;

                    // Skip barriers out of interaction reach (scan-box corners).
                    if (horiz2 > OPEN_RANGE_SQ) continue;
                    // Skip the doorway the bot is still standing in.
                    if (horiz2 < 1.6 * 1.6) continue;
                    // Skip doors ahead of the bot - it may want to walk through.
                    if (ddx * vel.x + ddz * vel.z > 0.0) continue;

                    toggle(mc, p);
                    cooldown = 6;
                    return;
                }
            }
        }
    }

    /** True for wooden/iron doors and fence gates - blocks with an OPEN state. */
    private static boolean isOpenableBarrier(BlockState st) {
        Block b = st.getBlock();
        return b instanceof DoorBlock || b instanceof FenceGateBlock;
    }

    private static void toggle(MinecraftClient mc, BlockPos pos) {
        // interactBlock acts on the passed hit result, so the door/gate toggles
        // without the camera having to turn around to face it.
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);

        try {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
        } catch (Throwable ignored) {
        }
    }
}
