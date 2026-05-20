package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

/**
 * Multi-stage forestry task. Not currently wired into the vote menu in v1.2.0
 * (slot 2 is fishing now), but kept around as a fully-working task because:
 *   - The bee-safety logic added here is useful regardless.
 *   - Future stream sessions might re-enable wood as a vote option.
 *   - Removing the file would leave dangling references in the legacy
 *     "wood" alias map in VoteManager.
 *
 *   LOGS    -> chop logs of any wood type until quota is met
 *   LEAVES  -> break leaves to drop sticks and saplings
 *   GRASS   -> break grass / tall grass to drop wheat seeds
 *
 * Bee safety: every couple of seconds during LOGS, the bot scans for
 * BEEHIVE / BEE_NEST blocks within {@code beehiveAvoidanceRadius}. If any
 * are found, the bot bails out of the current tree, hardResets Baritone,
 * and runs the explore command to push outward to a fresh area before
 * trying logs again. Bee swarms wreck a 24/7 stream, so the cost of
 * skipping a tree is much smaller than the cost of getting the bot stung
 * to death on camera.
 *
 * Each stage tracks its own progress; if no inventory change happens for a
 * while, the task triggers an explore command to push the bot to fresh
 * chunks before retrying. This way the bot doesn't sit idle in a depleted
 * area waiting for trees to grow back.
 *
 * Display name stays "Chopping wood" the entire time so chat sees the
 * activity they voted on, regardless of what stage we're in.
 */
public class WoodGatheringTask implements Task {

    private static final String[] LOG_BLOCKS = {
        "oak_log", "birch_log", "spruce_log", "jungle_log",
        "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log"
    };
    private static final String[] LEAF_BLOCKS = {
        "oak_leaves", "birch_leaves", "spruce_leaves", "jungle_leaves",
        "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "cherry_leaves",
        "azalea_leaves", "flowering_azalea_leaves"
    };
    private static final String[] GRASS_BLOCKS = {
        "short_grass", "tall_grass", "fern", "large_fern"
    };

    /** Seconds without inventory progress before the bot pushes outward. */
    private static final int EXPLORE_AFTER_SECONDS = 25;
    /** How long to spend exploring before re-issuing the mine. */
    private static final int EXPLORE_DURATION_SECONDS = 18;
    /** Max explore-then-retry cycles per stage before giving up that stage. */
    private static final int MAX_EXPLORE_CYCLES = 3;
    /** Bee safety scan cadence in ticks. */
    private static final int BEE_SCAN_INTERVAL_TICKS = 40;  // every 2s

    /** Saplings/sticks combined target for the LEAVES stage. */
    private static final int LEAF_DROPS_TARGET = 12;
    /** Seeds target for the GRASS stage. */
    private static final int SEEDS_TARGET = 16;

    private enum Stage { LOGS, EXPLORE_FOR_LOGS, LEAVES, EXPLORE_FOR_LEAVES, GRASS, EXPLORE_FOR_GRASS, DONE }

    private Stage   stage = Stage.LOGS;
    private int     stageStartTick;
    private int     startingLogs, startingLeafDrops, startingSeeds;
    private int     lastProgressTick;
    private int     lastProgressCount;
    private int     exploreCycles;
    private int     lastBeeScanTick;
    private boolean savedForCombat;
    private Stage   savedStage;

    @Override public String displayName() { return "Chopping wood"; }
    @Override public String id() { return "wood"; }

    @Override
    public void start() {
        var p = MinecraftClient.getInstance().player;
        if (p == null) return;
        startingLogs       = countLogs(p);
        startingLeafDrops  = countLeafDrops(p);
        startingSeeds      = countSeeds(p);
        enterStage(Stage.LOGS);
    }

    @Override
    public boolean tick() {
        var p = MinecraftClient.getInstance().player;
        if (p == null) return false;

        switch (stage) {
            case LOGS -> {
                // Bee safety: bail out of any tree near a beehive. Chopping
                // a log block adjacent to a beehive aggros the entire colony
                // and the swarm will follow the bot for blocks. Cheaper to
                // just leave this tree alone and find another.
                if (isBeehiveNearby(p)) {
                    ChatPilotMod.LOGGER.info("[ChatPilot] Beehive nearby, leaving this tree");
                    if (exploreCycles < MAX_EXPLORE_CYCLES) {
                        exploreCycles++;
                        enterStage(Stage.EXPLORE_FOR_LOGS);
                    } else {
                        // Already exhausted explore retries; advance to leaves
                        // which the bot can scrape from elsewhere.
                        enterStage(Stage.LEAVES);
                    }
                    return false;
                }

                int got = countLogs(p) - startingLogs;
                if (got > lastProgressCount) {
                    lastProgressCount = got;
                    lastProgressTick = clientTick();
                }
                if (got >= ChatPilotClient.CONFIG.woodLogQuota) {
                    enterStage(Stage.LEAVES);
                    return false;
                }
                if (!ChatPilotClient.BARITONE.isMining()) {
                    ChatPilotClient.BARITONE.mineBlocks(0, LOG_BLOCKS);
                }
                if (clientTick() - lastProgressTick > EXPLORE_AFTER_SECONDS * 20) {
                    if (exploreCycles < MAX_EXPLORE_CYCLES) {
                        exploreCycles++;
                        enterStage(Stage.EXPLORE_FOR_LOGS);
                    } else {
                        ChatPilotMod.LOGGER.info("[ChatPilot] Logs exhausted, advancing to leaves");
                        enterStage(Stage.LEAVES);
                    }
                }
            }
            case EXPLORE_FOR_LOGS -> {
                if (ticksInStage() > EXPLORE_DURATION_SECONDS * 20) {
                    lastProgressTick = clientTick();
                    enterStage(Stage.LOGS);
                }
            }
            case LEAVES -> {
                int got = countLeafDrops(p) - startingLeafDrops;
                if (got > lastProgressCount) {
                    lastProgressCount = got;
                    lastProgressTick = clientTick();
                }
                if (got >= LEAF_DROPS_TARGET) {
                    enterStage(Stage.GRASS);
                    return false;
                }
                if (!ChatPilotClient.BARITONE.isMining()) {
                    ChatPilotClient.BARITONE.mineBlocks(0, LEAF_BLOCKS);
                }
                if (clientTick() - lastProgressTick > EXPLORE_AFTER_SECONDS * 20) {
                    if (exploreCycles < MAX_EXPLORE_CYCLES) {
                        exploreCycles++;
                        enterStage(Stage.EXPLORE_FOR_LEAVES);
                    } else {
                        ChatPilotMod.LOGGER.info("[ChatPilot] Leaves exhausted, advancing to grass");
                        enterStage(Stage.GRASS);
                    }
                }
            }
            case EXPLORE_FOR_LEAVES -> {
                if (ticksInStage() > EXPLORE_DURATION_SECONDS * 20) {
                    lastProgressTick = clientTick();
                    enterStage(Stage.LEAVES);
                }
            }
            case GRASS -> {
                int got = countSeeds(p) - startingSeeds;
                if (got > lastProgressCount) {
                    lastProgressCount = got;
                    lastProgressTick = clientTick();
                }
                if (got >= SEEDS_TARGET) {
                    enterStage(Stage.DONE);
                    return true;
                }
                if (!ChatPilotClient.BARITONE.isMining()) {
                    ChatPilotClient.BARITONE.mineBlocks(0, GRASS_BLOCKS);
                }
                if (clientTick() - lastProgressTick > EXPLORE_AFTER_SECONDS * 20) {
                    if (exploreCycles < MAX_EXPLORE_CYCLES) {
                        exploreCycles++;
                        enterStage(Stage.EXPLORE_FOR_GRASS);
                    } else {
                        ChatPilotMod.LOGGER.info("[ChatPilot] Grass exhausted, finishing wood task");
                        enterStage(Stage.DONE);
                        return true;
                    }
                }
            }
            case EXPLORE_FOR_GRASS -> {
                if (ticksInStage() > EXPLORE_DURATION_SECONDS * 20) {
                    lastProgressTick = clientTick();
                    enterStage(Stage.GRASS);
                }
            }
            case DONE -> { return true; }
        }
        return false;
    }

    private void enterStage(Stage next) {
        ChatPilotMod.LOGGER.info("[ChatPilot] Wood stage {} -> {}", stage, next);
        stage = next;
        stageStartTick = clientTick();
        lastProgressTick = clientTick();
        lastProgressCount = 0;
        exploreCycles = 0;
        ChatPilotClient.BARITONE.hardReset();
        switch (next) {
            case LOGS                -> ChatPilotClient.BARITONE.mineBlocks(0, LOG_BLOCKS);
            case EXPLORE_FOR_LOGS    -> ChatPilotClient.BARITONE.run("explore");
            case LEAVES              -> ChatPilotClient.BARITONE.mineBlocks(0, LEAF_BLOCKS);
            case EXPLORE_FOR_LEAVES  -> ChatPilotClient.BARITONE.run("explore");
            case GRASS               -> ChatPilotClient.BARITONE.mineBlocks(0, GRASS_BLOCKS);
            case EXPLORE_FOR_GRASS   -> ChatPilotClient.BARITONE.run("explore");
            case DONE                -> {}
        }
    }

    @Override
    public boolean onStuck() {
        ChatPilotMod.LOGGER.info("[ChatPilot] Wood stall recovery from stage {}", stage);
        ChatPilotClient.BARITONE.hardReset();
        switch (stage) {
            case LOGS, EXPLORE_FOR_LOGS -> {
                if (exploreCycles < MAX_EXPLORE_CYCLES) { exploreCycles++; enterStage(Stage.EXPLORE_FOR_LOGS); }
                else enterStage(Stage.LEAVES);
                return true;
            }
            case LEAVES, EXPLORE_FOR_LEAVES -> {
                if (exploreCycles < MAX_EXPLORE_CYCLES) { exploreCycles++; enterStage(Stage.EXPLORE_FOR_LEAVES); }
                else enterStage(Stage.GRASS);
                return true;
            }
            case GRASS, EXPLORE_FOR_GRASS -> {
                if (exploreCycles < MAX_EXPLORE_CYCLES) { exploreCycles++; enterStage(Stage.EXPLORE_FOR_GRASS); }
                else enterStage(Stage.DONE);
                return true;
            }
            case DONE -> { return false; }
        }
        return false;
    }

    @Override
    public void onCombatStart() {
        savedForCombat = true;
        savedStage = stage;
        ChatPilotClient.BARITONE.stop();
    }

    @Override
    public void onCombatEnd() {
        if (!savedForCombat) return;
        savedForCombat = false;
        stage = savedStage;
        stageStartTick = clientTick();
        switch (stage) {
            case LOGS, EXPLORE_FOR_LOGS     -> ChatPilotClient.BARITONE.mineBlocks(0, LOG_BLOCKS);
            case LEAVES, EXPLORE_FOR_LEAVES -> ChatPilotClient.BARITONE.mineBlocks(0, LEAF_BLOCKS);
            case GRASS, EXPLORE_FOR_GRASS   -> ChatPilotClient.BARITONE.mineBlocks(0, GRASS_BLOCKS);
            case DONE                       -> {}
        }
    }

    @Override
    public void cancel() { ChatPilotClient.BARITONE.hardReset(); }

    /* helpers */

    /**
     * Throttled scan for beehive / bee_nest blocks within
     * {@link com.grammacrackers.chatpilot.config.ChatPilotConfig#beehiveAvoidanceRadius}
     * blocks of the player. The cadence (every 2s) keeps this cheap; the
     * beehives don't move so we don't need every-tick precision.
     */
    private boolean isBeehiveNearby(PlayerEntity p) {
        if (clientTick() - lastBeeScanTick < BEE_SCAN_INTERVAL_TICKS) return false;
        lastBeeScanTick = clientTick();
        var mc = MinecraftClient.getInstance();
        if (mc.world == null) return false;
        int r = Math.max(1, ChatPilotClient.CONFIG.beehiveAvoidanceRadius);
        BlockPos here = p.getBlockPos();
        for (BlockPos bp : BlockPos.iterate(
                here.add(-r, -3, -r),
                here.add( r,  3,  r))) {
            try {
                Block b = mc.world.getBlockState(bp).getBlock();
                if (b == Blocks.BEEHIVE || b == Blocks.BEE_NEST) {
                    ChatPilotMod.LOGGER.info("[ChatPilot] Beehive at {} within {} blocks", bp, r);
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private int ticksInStage() { return clientTick() - stageStartTick; }

    private static int clientTick() {
        return (int) (com.grammacrackers.chatpilot.event.TickClock.now() & 0x7fffffff);
    }

    private static int countLogs(PlayerEntity p) {
        int total = 0;
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            String id = Registries.ITEM.getId(s.getItem()).getPath();
            if (id.endsWith("_log")) total += s.getCount();
        }
        return total;
    }

    /** Sticks + any sapling = leaf-stage progress. */
    private static int countLeafDrops(PlayerEntity p) {
        int total = 0;
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            String id = Registries.ITEM.getId(s.getItem()).getPath();
            if (id.equals("stick") || id.endsWith("_sapling") || id.equals("mangrove_propagule")) {
                total += s.getCount();
            }
        }
        return total;
    }

    private static int countSeeds(PlayerEntity p) {
        int total = 0;
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            String id = Registries.ITEM.getId(s.getItem()).getPath();
            if (id.equals("wheat_seeds") || id.equals("fern") || id.equals("large_fern")) {
                total += s.getCount();
            }
        }
        return total;
    }
}
