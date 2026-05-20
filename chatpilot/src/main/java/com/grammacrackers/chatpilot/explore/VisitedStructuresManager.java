package com.grammacrackers.chatpilot.explore;

import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent record of every structure the bot has already explored. The
 * Explore and Mystery tasks consult this manager so chat never gets the same
 * pyramid or trial chamber back-to-back, and gameplay feels fresh every vote.
 *
 * Storage: a single JSON file at config/chatpilot/visited.json. Entries are
 * (kind, dimension, x, y, z) tuples. We compare new candidates by squared
 * distance to ALL previous entries of the same kind in the same dimension.
 *
 * Forgetfulness: after a (large) number of entries the oldest are pruned so
 * the file never grows without bound on a 24/7 stream.
 */
public class VisitedStructuresManager {

    public static final int MIN_DISTINCT_DISTANCE = 80;
    public static final int MAX_ENTRIES = 256;

    public static class Entry {
        public String kind;        // "village", "trial_chamber", etc.
        public String dimension;   // "minecraft:overworld" etc.
        public int x, y, z;
        public long timestamp;     // millis since epoch (for pruning order)

        public Entry() {}
        public Entry(String kind, String dimension, BlockPos pos) {
            this.kind = kind;
            this.dimension = dimension;
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());

    public VisitedStructuresManager() {
        load();
    }

    /**
     * Adds a structure location to the visited list. The bot will avoid
     * picking another structure of the same kind within MIN_DISTINCT_DISTANCE
     * of this point.
     */
    public void markVisited(String kind, String dimension, BlockPos pos) {
        synchronized (entries) {
            entries.add(new Entry(kind, dimension, pos));
            // Prune oldest entries if we cross the cap. Keeps file size bounded.
            while (entries.size() > MAX_ENTRIES) {
                entries.remove(0);
            }
        }
        save();
        ChatPilotMod.LOGGER.info("[ChatPilot] Marked visited: {} at {} ({}). Total: {}",
            kind, pos, dimension, entries.size());
    }

    /**
     * True if {@code candidate} is within MIN_DISTINCT_DISTANCE of any
     * previously visited structure of the same kind in the same dimension.
     */
    public boolean isNearVisited(String kind, String dimension, BlockPos candidate) {
        synchronized (entries) {
            for (Entry e : entries) {
                if (!e.kind.equals(kind)) continue;
                if (!e.dimension.equals(dimension)) continue;
                long dx = (long) candidate.getX() - e.x;
                long dz = (long) candidate.getZ() - e.z;
                if (dx * dx + dz * dz <= (long) MIN_DISTINCT_DISTANCE * MIN_DISTINCT_DISTANCE) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if any structure of the given kind has ever been visited. */
    public boolean hasVisitedKind(String kind, String dimension) {
        synchronized (entries) {
            for (Entry e : entries) {
                if (e.kind.equals(kind) && e.dimension.equals(dimension)) return true;
            }
        }
        return false;
    }

    public int totalVisited() {
        synchronized (entries) { return entries.size(); }
    }

    /* ------- persistence ------- */

    private Path file() {
        return FabricLoader.getInstance().getConfigDir()
            .resolve(ChatPilotMod.MOD_ID).resolve("visited.json");
    }

    private void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            String json = Files.readString(f);
            Type t = new TypeToken<List<Entry>>(){}.getType();
            List<Entry> read = new GsonBuilder().create().fromJson(json, t);
            if (read != null) {
                synchronized (entries) {
                    entries.clear();
                    entries.addAll(read);
                }
                ChatPilotMod.LOGGER.info("[ChatPilot] Loaded {} visited structures", read.size());
            }
        } catch (IOException e) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] visited.json load failed", e);
        }
    }

    public void save() {
        try {
            Path f = file();
            if (!Files.exists(f.getParent())) Files.createDirectories(f.getParent());
            String json;
            synchronized (entries) {
                json = new GsonBuilder().setPrettyPrinting().create().toJson(entries);
            }
            Files.writeString(f, json);
        } catch (IOException e) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] visited.json save failed", e);
        }
    }
}
