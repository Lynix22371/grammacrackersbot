package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

public final class MiningStaging {
    private MiningStaging() {}

    public static BlockPos stagingSurfacePos(MinecraftClient mc, int distance) {
        BlockPos bed = ChatPilotClient.HOME.getBedPos();

        if (bed == null) {
            return mc.player == null ? BlockPos.ORIGIN : mc.player.getBlockPos();
        }

        int d = Math.max(
                ChatPilotClient.CONFIG.houseProtectionRadius + 10,
                distance
        );

        double bearing = Math.toRadians(ChatPilotClient.CONFIG.miningStagingBearingDegrees);

        // Minecraft X/Z convention:
        // 0 deg -> +Z, 90 deg -> -X, 180 deg -> -Z, 270 deg -> +X
        int dx = (int) Math.round(-Math.sin(bearing) * d);
        int dz = (int) Math.round(Math.cos(bearing) * d);

        int x = bed.getX() + dx;
        int z = bed.getZ() + dz;

        int y = bed.getY();

        if (mc != null && mc.world != null) {
            try {
                y = mc.world.getTopY(
                        Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                        x,
                        z
                );
            } catch (Throwable ignored) {
                y = bed.getY();
            }
        }

        return new BlockPos(x, y, z);
    }

    public static boolean isNearXZ(BlockPos a, BlockPos b, int radius) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }
}
