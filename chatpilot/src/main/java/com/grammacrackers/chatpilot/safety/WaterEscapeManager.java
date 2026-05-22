
package com.grammacrackers.chatpilot.safety;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class WaterEscapeManager {
    private boolean escaping = false;
    private int submergedTicks = 0;
    private int escapeTicks = 0;
    private int repathCooldown = 0;
    private int wiggleTicks = 0;
    private BlockPos currentSurfaceTarget = null;

    public boolean tick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) {
            reset(mc);
            return false;
        }

        if (ChatPilotClient.CONFIG == null || !ChatPilotClient.CONFIG.waterEscapeEnabled) {
            reset(mc);
            return false;
        }

        ClientPlayerEntity p = mc.player;

        boolean headInWater = isHeadInWater(mc);
        boolean bodyInWater = isBodyInWater(mc);

        if (headInWater) {
            submergedTicks++;
        } else {
            submergedTicks = 0;
        }

        boolean airLow = p.getAir() <= ChatPilotClient.CONFIG.waterEscapeStartAirTicks;
        boolean shouldEscape =
                headInWater &&
                (submergedTicks >= ChatPilotClient.CONFIG.waterEscapeSubmergedTicks || airLow);

        if (!shouldEscape && !escaping) {
            return false;
        }

        if (!headInWater && escaping) {
            ChatPilotMod.LOGGER.info("[ChatPilot][WaterEscape] Reached air");
            reset(mc);
            return false;
        }

        if (!bodyInWater && escaping) {
            reset(mc);
            return false;
        }

        if (!escaping) {
            startEscape(mc);
        }

        escapeTicks++;

        if (repathCooldown-- <= 0 || currentSurfaceTarget == null) {
            repathCooldown = 20;
            currentSurfaceTarget = findNearestSurfaceAir(mc, p.getBlockPos());
        }

        // Stop any path/task movement fighting us.
        ChatPilotClient.BARITONE.stop();

        // Swim upward.
        mc.options.jumpKey.setPressed(true);
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.backKey.setPressed(false);

        // If we are stuck against a wall/shipwreck block, wiggle sideways.
        wiggleTicks++;
        boolean wiggleLeft = (wiggleTicks / 20) % 2 == 0;
        mc.options.leftKey.setPressed(p.horizontalCollision && wiggleLeft);
        mc.options.rightKey.setPressed(p.horizontalCollision && !wiggleLeft);

        if (currentSurfaceTarget != null) {
            lookAt(p, Vec3d.ofCenter(currentSurfaceTarget));
        } else {
            // No surface target found; pure emergency swim up.
            p.setPitch(-65.0f);
        }

        // Safety ceiling: if we have been trying for a long time, ask Baritone
        // to surface once, then continue manual swimming.
        if (escapeTicks == ChatPilotClient.CONFIG.waterEscapeBaritoneSurfaceAfterTicks) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][WaterEscape] Long underwater escape, trying Baritone surface");
            ChatPilotClient.BARITONE.run("surface");
        }

        return true;
    }

    private void startEscape(MinecraftClient mc) {
        escaping = true;
        escapeTicks = 0;
        repathCooldown = 0;
        wiggleTicks = 0;
        currentSurfaceTarget = null;

        ChatPilotMod.LOGGER.warn("[ChatPilot][WaterEscape] Emergency water escape engaged");

        ChatPilotClient.BARITONE.hardReset();

        // If fishing line is out, reel/cancel it once so the fishing task does
        // not keep the bot staring at a bobber while underwater.
        try {
            if (mc.player.fishHook != null && mc.interactionManager != null) {
                if (mc.player.getMainHandStack().isOf(Items.FISHING_ROD)) {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                } else if (mc.player.getOffHandStack().isOf(Items.FISHING_ROD)) {
                    mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void reset(MinecraftClient mc) {
        escaping = false;
        submergedTicks = 0;
        escapeTicks = 0;
        repathCooldown = 0;
        wiggleTicks = 0;
        currentSurfaceTarget = null;

        if (mc != null && mc.options != null) {
            mc.options.jumpKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
        }
    }

    private static boolean isHeadInWater(MinecraftClient mc) {
        BlockPos eye = BlockPos.ofFloored(mc.player.getEyePos());
        return mc.world.getFluidState(eye).isIn(FluidTags.WATER);
    }

    private static boolean isBodyInWater(MinecraftClient mc) {
        BlockPos feet = mc.player.getBlockPos();
        return mc.player.isTouchingWater()
                || mc.world.getFluidState(feet).isIn(FluidTags.WATER)
                || mc.world.getFluidState(feet.up()).isIn(FluidTags.WATER);
    }

    private static BlockPos findNearestSurfaceAir(MinecraftClient mc, BlockPos origin) {
        int radius = Math.max(4, ChatPilotClient.CONFIG.waterEscapeSurfaceSearchRadius);
        int height = Math.max(8, ChatPilotClient.CONFIG.waterEscapeSurfaceSearchHeight);

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        int minY = Math.max(mc.world.getBottomY(), origin.getY() - 2);
        int maxY = Math.min(mc.world.getTopYInclusive(), origin.getY() + height);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int y = origin.getY(); y <= maxY; y++) {
                    BlockPos water = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                    BlockPos air = water.up();

                    if (air.getY() > maxY) continue;

                    boolean isWater = mc.world.getFluidState(water).isIn(FluidTags.WATER);
                    boolean hasAirAbove = mc.world.getBlockState(air).isAir()
                            && mc.world.getFluidState(air).isEmpty();

                    if (!isWater || !hasAirAbove) continue;

                    double dist = air.getSquaredDistance(origin);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = air.toImmutable();
                    }

                    break;
                }
            }
        }

        if (best != null) return best;

        // Fallback: look straight up from current position.
        for (int y = origin.getY(); y <= maxY; y++) {
            BlockPos p = new BlockPos(origin.getX(), y, origin.getZ());
            if (mc.world.getBlockState(p).isAir() && mc.world.getFluidState(p).isEmpty()) {
                return p.toImmutable();
            }
        }

        return null;
    }

    private static void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();

        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;

        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));

        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.setPitch(pitch);
    }
}
