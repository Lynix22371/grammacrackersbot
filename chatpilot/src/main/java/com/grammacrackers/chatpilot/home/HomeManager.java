package com.grammacrackers.chatpilot.home;

import com.google.gson.GsonBuilder;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class HomeManager {

    public static class HomeData {
        public Integer bedX, bedY, bedZ;
        public String  dimension;
        public Integer hopperX, hopperY, hopperZ;
        /**
         * Cactus position for trashing items. New in v1.2.0. Items in the
         * config trash list get dropped onto this cactus during the
         * return-home flow so they're destroyed instead of clogging chests.
         */
        public Integer cactusX, cactusY, cactusZ;
    }

    private HomeData data = new HomeData();

    public HomeManager() {
        load();
    }

    public boolean hasHome() {
        return data.bedX != null && data.bedY != null && data.bedZ != null;
    }

    public boolean hasHopper() {
        return data.hopperX != null && data.hopperY != null && data.hopperZ != null;
    }

    public boolean hasCactus() {
        return data.cactusX != null && data.cactusY != null && data.cactusZ != null;
    }

    public BlockPos getBedPos() {
        return hasHome() ? new BlockPos(data.bedX, data.bedY, data.bedZ) : null;
    }

    public BlockPos getHopperPos() {
        return hasHopper() ? new BlockPos(data.hopperX, data.hopperY, data.hopperZ) : null;
    }

    public BlockPos getCactusPos() {
        return hasCactus() ? new BlockPos(data.cactusX, data.cactusY, data.cactusZ) : null;
    }

    public void setHopper(BlockPos pos) {
        data.hopperX = pos.getX();
        data.hopperY = pos.getY();
        data.hopperZ = pos.getZ();
        save();
        ChatPilotMod.LOGGER.info("[ChatPilot] Hopper drop point set to {}", pos);
    }

    public void clearHopper() {
        data.hopperX = data.hopperY = data.hopperZ = null;
        save();
        ChatPilotMod.LOGGER.info("[ChatPilot] Hopper drop point cleared");
    }

    public void setCactus(BlockPos pos) {
        data.cactusX = pos.getX();
        data.cactusY = pos.getY();
        data.cactusZ = pos.getZ();
        save();
        ChatPilotMod.LOGGER.info("[ChatPilot] Trash cactus set to {}", pos);
    }

    public void clearCactus() {
        data.cactusX = data.cactusY = data.cactusZ = null;
        save();
        ChatPilotMod.LOGGER.info("[ChatPilot] Trash cactus cleared");
    }

    public void setBed(BlockPos pos, String dim) {
        data.bedX = pos.getX();
        data.bedY = pos.getY();
        data.bedZ = pos.getZ();
        data.dimension = dim;
        save();
        ChatPilotMod.LOGGER.info("[ChatPilot] Home set to {} ({})", pos, dim);
    }

    /** Auto-detect: walk to nearest bed within radius, set it as home. */
    public void autoSetFromNearestBed(int radius) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        BlockPos origin = mc.player.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(origin.add(-radius, -radius, -radius),
                                           origin.add(radius, radius, radius))) {
            Block b = mc.world.getBlockState(p).getBlock();
            if (b instanceof BedBlock) {
                double d = p.getSquaredDistance(origin);
                if (d < bestDist) { bestDist = d; best = p.toImmutable(); }
            }
        }
        if (best != null) setBed(best, mc.world.getRegistryKey().getValue().toString());
    }

    /** Returns true if pos is within the no-grief radius around the bed. */
    public boolean isProtected(BlockPos pos, int radius) {
        if (!hasHome()) return false;
        BlockPos bed = getBedPos();
        int dx = pos.getX() - bed.getX();
        int dy = pos.getY() - bed.getY();
        int dz = pos.getZ() - bed.getZ();
        return (dx*dx + dy*dy + dz*dz) <= (radius * radius);
    }

    /** All chest positions within radius of the bed. */
    public List<BlockPos> findNearbyChests(World world, int radius) {
        List<BlockPos> out = new ArrayList<>();
        if (!hasHome()) return out;
        BlockPos bed = getBedPos();
        for (BlockPos p : BlockPos.iterate(bed.add(-radius, -radius, -radius),
                                           bed.add(radius, radius, radius))) {
            if (world.getBlockState(p).getBlock() instanceof ChestBlock) {
                out.add(p.toImmutable());
            }
        }
        return out;
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(ChatPilotMod.MOD_ID).resolve("home.json");
    }

    private void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                data = new GsonBuilder().create().fromJson(Files.readString(f), HomeData.class);
                if (data == null) data = new HomeData();
            }
        } catch (IOException e) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] home load failed", e);
        }
    }

    public void save() {
        try {
            Path f = file();
            if (!Files.exists(f.getParent())) Files.createDirectories(f.getParent());
            Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(data));
        } catch (IOException e) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] home save failed", e);
        }
    }
}
