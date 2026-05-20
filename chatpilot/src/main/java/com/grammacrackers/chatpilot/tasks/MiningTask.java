package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Mining task. v1.2.0 refocus on emerald, gold, and coal.
 *
 *   EMERALD: rare, exciting, only generates in mountain biomes. Low quota
 *            (4) so any small find satisfies the stage and the bot moves on
 *            without grinding the same vein for an hour. The explore-then-
 *            retry guard kicks the bot to fresh chunks if no emerald shows
 *            up quickly, then advances to gold.
 *   GOLD:    moderate frequency. Quota 8.
 *   COAL:    common and reliable. Quota 16. Always finds some, so this is
 *            effectively the floor of "the bot accomplished something".
 *
 * Iron, copper, lapis, and diamond are NOT in the active scan list. Baritone
 * still picks them up incidentally as the bot mines through stone, but they
 * don't drive the search any more. Copper and lapis are even on the trash
 * list now so any incidental drops get tossed at the cactus on return home.
 *
 * Bug fix carried over from 1.1.0: cycle counter is keyed on ore type, so
 * retries within the same ore type accumulate properly and the
 * MAX_EXPLORE_CYCLES guard actually trips.
 */
public class MiningTask implements Task {

    private enum Stage { EMERALD, EMERALD_EXPLORE,
                         GOLD,    GOLD_EXPLORE,
                         COAL,    COAL_EXPLORE,
                         STONE_FALLBACK, DONE }

    /** Tracks which ore type is "active" so we know when to reset cycle counters. */
    private enum OreType { EMERALD, GOLD, COAL, NONE }

    private static final int EXPLORE_AFTER_SECONDS    = 25;
    private static final int EXPLORE_DURATION_SECONDS = 18;
    private static final int MAX_EXPLORE_CYCLES       = 2;
    private static final int STONE_FALLBACK_SECONDS   = 45;

    private Stage   stage = Stage.EMERALD;
    private OreType currentOre = OreType.NONE;
    private int     stageStartTick;

    private int   emeraldStart, goldStart, coalStart;
    private int   lastProgressTick;
    private int   lastProgressCount;
    /** Counts explore-then-retry attempts FOR THE CURRENT ore type. Resets only on ore-type change. */
    private int   exploreCycles;
    private boolean savedForCombat;
    private Stage   savedStage;

    @Override public String displayName() { return "Mining ores"; }
    @Override public String id() { return "mine"; }

    @Override
    public void start() {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        emeraldStart = countItem(mc.player, Items.EMERALD);
        goldStart    = countItem(mc.player, Items.RAW_GOLD);
        coalStart    = countItem(mc.player, Items.COAL);
        ChatPilotClient.BARITONE.hardReset();
        enterStage(Stage.EMERALD);
        ChatPilotMod.LOGGER.info("[ChatPilot] Mining started at {}", mc.player.getBlockPos());
    }

    @Override
    public boolean tick() {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;

        switch (stage) {
            case EMERALD -> tickOreStage(mc, Items.EMERALD, emeraldStart,
                ChatPilotClient.CONFIG.miningOreQuotaEmerald,
                Stage.EMERALD_EXPLORE, Stage.GOLD, EMERALD_BLOCKS);
            case GOLD    -> tickOreStage(mc, Items.RAW_GOLD, goldStart,
                ChatPilotClient.CONFIG.miningOreQuotaGold,
                Stage.GOLD_EXPLORE, Stage.COAL, GOLD_BLOCKS);
            case COAL    -> tickOreStage(mc, Items.COAL, coalStart,
                ChatPilotClient.CONFIG.miningOreQuotaCoal,
                Stage.COAL_EXPLORE, Stage.STONE_FALLBACK, COAL_BLOCKS);

            case EMERALD_EXPLORE,
                 GOLD_EXPLORE,
                 COAL_EXPLORE -> tickExploreStage();

            case STONE_FALLBACK -> {
                if (ticksInStage() > STONE_FALLBACK_SECONDS * 20) {
                    enterStage(Stage.DONE);
                    return true;
                }
                if (!ChatPilotClient.BARITONE.isMining()) {
                    ChatPilotClient.BARITONE.mineBlocks(0, "stone", "deepslate", "cobbled_deepslate");
                }
            }
            case DONE -> { return true; }
        }
        return false;
    }

    private void tickOreStage(MinecraftClient mc, net.minecraft.item.Item targetItem,
                              int startCount, int quota, Stage exploreStage, Stage nextStage,
                              String[] mineBlocks) {
        int got = countItem(mc.player, targetItem) - startCount;
        if (got > lastProgressCount) {
            lastProgressCount = got;
            lastProgressTick  = clientTick();
        }
        if (got >= quota) {
            ChatPilotMod.LOGGER.info("[ChatPilot] {} quota met ({}/{}), advancing", stage, got, quota);
            enterStage(nextStage);
            return;
        }
        if (!ChatPilotClient.BARITONE.isMining()) {
            ChatPilotClient.BARITONE.mineBlocks(0, mineBlocks);
        }
        if (clientTick() - lastProgressTick > EXPLORE_AFTER_SECONDS * 20) {
            if (exploreCycles < MAX_EXPLORE_CYCLES) {
                exploreCycles++;
                ChatPilotMod.LOGGER.info("[ChatPilot] {} stalled, exploring outward (cycle {}/{})",
                    stage, exploreCycles, MAX_EXPLORE_CYCLES);
                enterStage(exploreStage);
            } else {
                ChatPilotMod.LOGGER.info("[ChatPilot] {} exhausted exploration, advancing", stage);
                enterStage(nextStage);
            }
        }
    }

    private void tickExploreStage() {
        if (ticksInStage() > EXPLORE_DURATION_SECONDS * 20) {
            switch (stage) {
                case EMERALD_EXPLORE -> enterStage(Stage.EMERALD);
                case GOLD_EXPLORE    -> enterStage(Stage.GOLD);
                case COAL_EXPLORE    -> enterStage(Stage.COAL);
                default              -> enterStage(Stage.STONE_FALLBACK);
            }
        }
    }

    private void enterStage(Stage next) {
        ChatPilotMod.LOGGER.info("[ChatPilot] Mining stage {} -> {}", stage, next);
        OreType nextOre = oreTypeOf(next);
        boolean oreChanged = nextOre != OreType.NONE && nextOre != currentOre;
        if (oreChanged) {
            currentOre = nextOre;
            exploreCycles = 0;
        }
        stage = next;
        stageStartTick = clientTick();
        lastProgressTick = clientTick();
        lastProgressCount = 0;
        ChatPilotClient.BARITONE.hardReset();
        switch (next) {
            case EMERALD           -> ChatPilotClient.BARITONE.mineBlocks(0, EMERALD_BLOCKS);
            case GOLD              -> ChatPilotClient.BARITONE.mineBlocks(0, GOLD_BLOCKS);
            case COAL              -> ChatPilotClient.BARITONE.mineBlocks(0, COAL_BLOCKS);
            case EMERALD_EXPLORE,
                 GOLD_EXPLORE,
                 COAL_EXPLORE      -> ChatPilotClient.BARITONE.run("explore");
            case STONE_FALLBACK    -> ChatPilotClient.BARITONE.mineBlocks(0,
                                          "stone", "deepslate", "cobbled_deepslate");
            case DONE              -> {}
        }
    }

    private static OreType oreTypeOf(Stage s) {
        return switch (s) {
            case EMERALD, EMERALD_EXPLORE -> OreType.EMERALD;
            case GOLD,    GOLD_EXPLORE    -> OreType.GOLD;
            case COAL,    COAL_EXPLORE    -> OreType.COAL;
            default                       -> OreType.NONE;
        };
    }

    @Override
    public boolean onStuck() {
        ChatPilotMod.LOGGER.info("[ChatPilot] Mining stall recovery from stage {}", stage);
        ChatPilotClient.BARITONE.hardReset();
        switch (stage) {
            case EMERALD, EMERALD_EXPLORE -> { advanceOrExplore(Stage.EMERALD_EXPLORE, Stage.GOLD);            return true; }
            case GOLD,    GOLD_EXPLORE    -> { advanceOrExplore(Stage.GOLD_EXPLORE,    Stage.COAL);            return true; }
            case COAL,    COAL_EXPLORE    -> { advanceOrExplore(Stage.COAL_EXPLORE,    Stage.STONE_FALLBACK);  return true; }
            case STONE_FALLBACK           -> { enterStage(Stage.DONE); return true; }
            case DONE                     -> { return false; }
        }
        return false;
    }

    private void advanceOrExplore(Stage exploreStage, Stage skipStage) {
        if (exploreCycles < MAX_EXPLORE_CYCLES) {
            exploreCycles++;
            enterStage(exploreStage);
        } else {
            enterStage(skipStage);
        }
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
        ChatPilotMod.LOGGER.info("[ChatPilot] Mining resuming at stage {}", savedStage);
        enterStage(savedStage);
    }

    @Override
    public void cancel() { ChatPilotClient.BARITONE.hardReset(); }

    /* ---------- ore tables ---------- */

    private static final String[] EMERALD_BLOCKS =
        { "emerald_ore", "deepslate_emerald_ore" };
    private static final String[] GOLD_BLOCKS =
        { "gold_ore", "deepslate_gold_ore", "nether_gold_ore" };
    private static final String[] COAL_BLOCKS =
        { "coal_ore", "deepslate_coal_ore" };

    /* ---------- helpers ---------- */

    private int ticksInStage() { return clientTick() - stageStartTick; }

    private static int clientTick() {
        return (int) (com.grammacrackers.chatpilot.event.TickClock.now() & 0x7fffffff);
    }

    private static int countItem(PlayerEntity p, net.minecraft.item.Item item) {
        int total = 0;
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == item) total += s.getCount();
        }
        return total;
    }
}
