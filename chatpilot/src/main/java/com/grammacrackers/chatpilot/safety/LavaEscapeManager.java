package com.grammacrackers.chatpilot.safety;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;

public class LavaEscapeManager {
    private boolean escaping = false;
    private int repathCooldown = 0;

    public boolean tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return false;

        boolean inLava =
                mc.player.isInLava()
                || mc.world.getFluidState(mc.player.getBlockPos()).isIn(FluidTags.LAVA)
                || mc.world.getFluidState(mc.player.getBlockPos().up()).isIn(FluidTags.LAVA);

        if (!inLava) {
            if (escaping) {
                escaping = false;
                releaseKeys(mc);
                ChatPilotMod.LOGGER.info("[ChatPilot] Lava escape finished");
            }
            return false;
        }

        if (!escaping) {
            escaping = true;
            repathCooldown = 0;
            ChatPilotMod.LOGGER.warn("[ChatPilot] Lava escape engaged");
            ChatPilotClient.BARITONE.hardReset();
            ChatPilotClient.STUCK.reset();

            if (ChatPilotClient.COMBAT != null) {
                ChatPilotClient.COMBAT.forceExit();
            }
        }

        // Manual swim/jump every tick; Baritone alone can be too slow in lava.
        mc.options.jumpKey.setPressed(true);
        mc.options.forwardKey.setPressed(true);

        if (--repathCooldown <= 0) {
            repathCooldown = 20;

            BlockPos safe = findNearestSafe(mc, mc.player.getBlockPos(), 10, 4);
            if (safe != null) {
                ChatPilotClient.BARITONE.gotoNear(safe, 1);
            } else {
                // No safe shore found; at least force upward movement.
                mc.player.setVelocity(mc.player.getVelocity().x, 0.18, mc.player.getVelocity().z);
            }
        }

        return true;
    }

    private static void releaseKeys(MinecraftClient mc) {
        mc.options.jumpKey.setPressed(false);
        mc.options.forwardKey.setPressed(false);
    }

    private static BlockPos findNearestSafe(MinecraftClient mc, BlockPos origin, int radius, int yRange) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int y = -yRange; y <= yRange; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos feet = origin.add(x, y, z);
                    if (!isSafeStandingSpot(mc, feet)) continue;

                    double d = feet.getSquaredDistance(origin);
                    if (d < bestDist) {
                        bestDist = d;
                        best = feet.toImmutable();
                    }
                }
            }
        }

        return best;
    }

    private static boolean isSafeStandingSpot(MinecraftClient mc, BlockPos feet) {
        BlockState feetState = mc.world.getBlockState(feet);
        BlockState headState = mc.world.getBlockState(feet.up());
        BlockState belowState = mc.world.getBlockState(feet.down());

        if (!feetState.getCollisionShape(mc.world, feet).isEmpty()) return false;
        if (!headState.getCollisionShape(mc.world, feet.up()).isEmpty()) return false;
        if (belowState.getCollisionShape(mc.world, feet.down()).isEmpty()) return false;

        if (mc.world.getFluidState(feet).isIn(FluidTags.LAVA)) return false;
        if (mc.world.getFluidState(feet.up()).isIn(FluidTags.LAVA)) return false;
        if (mc.world.getFluidState(feet.down()).isIn(FluidTags.LAVA)) return false;

        if (feetState.isOf(Blocks.FIRE) || feetState.isOf(Blocks.SOUL_FIRE)) return false;
        if (belowState.isOf(Blocks.MAGMA_BLOCK) || belowState.isOf(Blocks.CACTUS)) return false;

        return true;
    }
}
