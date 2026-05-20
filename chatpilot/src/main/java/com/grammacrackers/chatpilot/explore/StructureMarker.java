package com.grammacrackers.chatpilot.explore;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Arrays;
import java.util.List;

/**
 * Catalog of structure types we can hunt for, plus the blocks that uniquely
 * identify each one. All entries here are SURFACE-or-ABOVE-WATER structures
 * by design. Anything that spawns underwater (shipwreck, ocean monument,
 * ocean ruin, buried treasure) is intentionally absent. Grandma's bot uses
 * water breathing as a safety net but we still avoid building a goal IN
 * water because pathfinding through liquid is unpredictable.
 *
 * Each marker is a vanilla block ID (minecraft:bell, etc.). A block scan
 * around the player checks for any of these in loaded chunks and the result
 * is the structure kind.
 */
public final class StructureMarker {

    public enum Mode {
        /** Surface explore: the bot wanders and loots, no expected combat. */
        EXPLORE,
        /** Mystery battle: trial chamber / ancient city, expect mobs. */
        MYSTERY
    }

    public static class Type {
        public final String  kind;          // "village", "trial_chamber", ...
        public final Mode    mode;
        public final String  primaryBlock;  // the block ID Baritone scans for
        public final List<String> alsoMatch;// other ids that also count
        public final int     exploreRadius; // how wide to wander for chests
        public final int     exploreSeconds;// how long to wander once on-site
        public final boolean avoidIfWaterAround; // skip if the block sits in water

        public Type(String kind, Mode mode, String primary, List<String> also,
                    int radius, int seconds, boolean avoidWater) {
            this.kind = kind;
            this.mode = mode;
            this.primaryBlock = primary;
            this.alsoMatch = also;
            this.exploreRadius = radius;
            this.exploreSeconds = seconds;
            this.avoidIfWaterAround = avoidWater;
        }
    }

    /** Surface structures the Explore vote can target. Non-exhaustive on
     *  purpose — we want highly distinctive markers so false positives are rare. */
    public static final List<Type> EXPLORE_TYPES = List.of(
        // Village: every village has at least one bell at its center.
        new Type("village", Mode.EXPLORE, "bell",
            List.of(), 24, 35, true),

        // Pillager outpost: the cage on top of the watchtower spawns a
        // pillager. The "pillager_outpost"-only block we can rely on is the
        // dark-oak fence layout but cage-spawner is more reliable.
        new Type("pillager_outpost", Mode.EXPLORE, "mob_spawner",
            List.of(), 18, 25, true),

        // Ruined portal: crying obsidian only generates here in the overworld
        // (it also exists in respawn anchors which are player-built — those
        // get filtered by avoidIfWaterAround/visited tracking effectively).
        new Type("ruined_portal", Mode.EXPLORE, "crying_obsidian",
            List.of("netherrack"), 14, 22, true),

        // Desert pyramid: chiseled sandstone is the floor pattern marker.
        new Type("desert_pyramid", Mode.EXPLORE, "chiseled_sandstone",
            List.of(), 18, 30, false),

        // Stronghold: end portal frames are 100% unique.
        new Type("stronghold", Mode.EXPLORE, "end_portal_frame",
            List.of(), 24, 45, true),

        // Mineshaft: rails on cave_air-supported wood. Track marker.
        new Type("mineshaft", Mode.EXPLORE, "rail",
            List.of("powered_rail", "detector_rail"), 22, 40, true),

        // Witch hut: a swamp cottage with a cauldron. We use cauldron WITH
        // crafting_table fallback. Cauldrons in villages are filtered by
        // distance from villages already explored, so this works ok.
        new Type("witch_hut", Mode.EXPLORE, "cauldron",
            List.of(), 12, 18, true),

        // Igloo: snow_block + carved_pumpkin combo would be ideal but we
        // pick on snow_block which is over-broad. Skip for now.

        // Woodland mansion: dark oak planks are the core build material.
        // Less unique than other markers, but pillagers don't spawn them.
        new Type("woodland_mansion", Mode.EXPLORE, "dark_oak_planks",
            List.of("dark_oak_log"), 28, 50, true)
    );

    /** Mystery destinations: dangerous structures with combat. */
    public static final List<Type> MYSTERY_TYPES = List.of(
        // Trial chambers (1.21+): the trial spawner is unique.
        new Type("trial_chamber", Mode.MYSTERY, "trial_spawner",
            List.of("vault"), 28, 60, false),

        // Ancient city / Deep dark: sculk shrieker is unique to the deep
        // dark biome and ancient city structure.
        new Type("deep_dark", Mode.MYSTERY, "sculk_shrieker",
            List.of("reinforced_deepslate", "soul_lantern"), 32, 60, false)
    );

    public static List<Type> typesFor(Mode mode) {
        return mode == Mode.MYSTERY ? MYSTERY_TYPES : EXPLORE_TYPES;
    }

    /**
     * True if there is water within {@code radius} blocks of {@code pos} in
     * any horizontal direction at or above the same Y. Used to skip
     * underwater shipwrecks the moment a marker scan picks one up.
     */
    public static boolean hasWaterAround(World world, BlockPos pos, int radius) {
        if (world == null) return false;
        for (BlockPos p : BlockPos.iterate(
                pos.add(-radius, -2, -radius),
                pos.add(radius, 2, radius))) {
            try {
                var fluidState = world.getFluidState(p);
                if (fluidState != null && !fluidState.isEmpty()) {
                    Identifier id = Registries.FLUID.getId(fluidState.getFluid());
                    if (id != null && id.getPath().contains("water")) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * Resolve a Type by scanning its primary or alternate block IDs against
     * the path of a candidate block. Returns null if no Type matches.
     */
    public static Type matchType(Mode mode, String blockPath) {
        if (blockPath == null) return null;
        for (Type t : typesFor(mode)) {
            if (blockPath.equals(t.primaryBlock)) return t;
            for (String alt : t.alsoMatch) {
                if (blockPath.equals(alt)) return t;
            }
        }
        return null;
    }

    /**
     * All marker block IDs (primary + alternates) for a given mode, deduped.
     * Caller can pass these directly to Baritone's mine command to drive the
     * bot toward any of these targets.
     */
    public static String[] allMarkerIds(Mode mode) {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (Type t : typesFor(mode)) {
            set.add(t.primaryBlock);
            set.addAll(t.alsoMatch);
        }
        return set.toArray(new String[0]);
    }

    private StructureMarker() {}

    /** Picks a random Type weighted equally across all configured types. */
    public static Type randomType(Mode mode) {
        List<Type> ts = typesFor(mode);
        if (ts.isEmpty()) return null;
        return ts.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(ts.size()));
    }

    /** All Types whose any-marker-id list contains the given path. Useful for
     *  tagging the kind of a found block during a scan. */
    public static List<Type> typesContaining(Mode mode, String blockPath) {
        var out = new java.util.ArrayList<Type>();
        for (Type t : typesFor(mode)) {
            if (t.primaryBlock.equals(blockPath)) { out.add(t); continue; }
            if (t.alsoMatch.contains(blockPath)) { out.add(t); }
        }
        return out;
    }

    /** Convenience: array of strings from a list. */
    public static String[] toArray(List<String> list) {
        return list.toArray(new String[0]);
    }

    /** Helpful for debug logging. */
    public static String describe(Type t) {
        return t.kind + " (" + t.primaryBlock + "+" + Arrays.toString(t.alsoMatch.toArray()) + ")";
    }
}
