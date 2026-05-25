package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import com.grammacrackers.chatpilot.chat.OreDemandTracker;
import net.minecraft.util.math.BlockPos;

/**
 * Mining task.
 *
 * The normal (non-voted) rotation works through every ore in priority order:
 *   DIAMOND -> IRON -> EMERALD -> GOLD -> LAPIS -> COAL
 * Each ore has its own quota (see ChatPilotConfig). Once a quota is met, or
 * exploration is exhausted, the bot advances to the next ore. After coal it
 * drops to a plain STONE_FALLBACK dig so the run always accomplishes something.
 *
 * Chat voting can still request any single ore directly (see CHAT_REQUESTED);
 * that request is satisfied first, then the normal rotation resumes.
 *
 * The cycle counter is keyed on ore type, so retries within the same ore type
 * accumulate properly and the MAX_EXPLORE_CYCLES guard actually trips.
 */
public class MiningTask implements Task {

    private enum Stage {
        GO_TO_STAGING,
        CHAT_REQUESTED,
        CHAT_REQUESTED_EXPLORE,
        EMERALD,
        EMERALD_EXPLORE,
        DIAMOND,
        DIAMOND_EXPLORE,
        GOLD,
        GOLD_EXPLORE,
        IRON,
        IRON_EXPLORE,
        LAPIS,
        LAPIS_EXPLORE,
        COAL,
        COAL_EXPLORE,
        STONE_FALLBACK,
        DONE
    }


    /** Tracks which ore type is "active" so we know when to reset cycle counters. */
    private enum OreType { EMERALD, DIAMOND, GOLD, IRON, LAPIS, COAL, NONE }

    private static final int EXPLORE_AFTER_SECONDS    = 25;
    private static final int EXPLORE_DURATION_SECONDS = 18;
    private static final int MAX_EXPLORE_CYCLES       = 2;
    /** Stone fallback runs long enough to fill the rest of the mining session. */
    private static final int STONE_FALLBACK_SECONDS   = 300;
    /** After a quota is met, keep mining target ore within this block radius. */
    private static final int ORE_FINISH_RADIUS        = 4;

    private OreDemandTracker.OreTarget chatTarget;
    private int chatTargetStartCount;
    private BlockPos miningStagingPos;

    private Stage   stage = Stage.DIAMOND;
    private OreType currentOre = OreType.NONE;
    private int     stageStartTick;

    private int   emeraldStart, diamondStart, goldStart, ironStart, lapisStart, coalStart;
    private int   lastProgressTick;
    private int   lastProgressCount;
    /** Counts explore-then-retry attempts FOR THE CURRENT ore type. Resets only on ore-type change. */
    private int   exploreCycles;
    private boolean savedForCombat;
    private Stage   savedStage;



    private static int countOreDrop(PlayerEntity p, OreDemandTracker.OreTarget target) {
        return switch (target) {
            case DIAMOND -> countItem(p, Items.DIAMOND);
            case IRON -> countItem(p, Items.RAW_IRON);
            case GOLD -> countItem(p, Items.RAW_GOLD);
            case EMERALD -> countItem(p, Items.EMERALD);
            case COAL -> countItem(p, Items.COAL);
            case REDSTONE -> countItem(p, Items.REDSTONE);
            case LAPIS -> countItem(p, Items.LAPIS_LAZULI);
            case COPPER -> countItem(p, Items.RAW_COPPER);
        };
    }

    @Override public String displayName() { return "Mining ores"; }
    @Override public String id() { return "mine"; }



    @Override
    public void start() {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
    
        emeraldStart = countItem(mc.player, Items.EMERALD);
        diamondStart = countItem(mc.player, Items.DIAMOND);
        goldStart    = countItem(mc.player, Items.RAW_GOLD);
        ironStart    = countItem(mc.player, Items.RAW_IRON);
        lapisStart   = countItem(mc.player, Items.LAPIS_LAZULI);
        coalStart    = countItem(mc.player, Items.COAL);
    
        ChatPilotClient.BARITONE.hardReset();
    
        miningStagingPos = null;
        chatTarget = null;
        currentOre = OreType.NONE;
        exploreCycles = 0;
    
        enterStage(Stage.GO_TO_STAGING);
    
        ChatPilotMod.LOGGER.info("[ChatPilot] Mining started at {}", mc.player.getBlockPos());
    }


    @Override
    public boolean tick() {
        var mc = MinecraftClient.getInstance();
        if (ChatPilotClient.CONFIG.miningStopNearBedrock && isTooCloseToBedrock(mc)) {
            ChatPilotMod.LOGGER.warn(
                    "[ChatPilot][Mining] Reached bedrock safety floor y={}, ending mining task",
                    mc.player.getBlockY()
            );

            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.DONE);
            return true;
        }
        if (mc.player == null) return false;

        switch (stage) {
            case CHAT_REQUESTED -> {
                if (chatTarget == null) {
                    enterStage(Stage.DIAMOND);
                    return false;
                }
            
                tickChatRequestedOreStage(mc);
            }
            
            case CHAT_REQUESTED_EXPLORE -> {
                if (ticksInStage() > EXPLORE_DURATION_SECONDS * 20) {
                    enterStage(Stage.CHAT_REQUESTED);
                }
            }
            case GO_TO_STAGING -> tickGoToStaging(mc);

            case DIAMOND -> tickOreStage(mc, Items.DIAMOND, diamondStart,
                ChatPilotClient.CONFIG.miningOreQuotaDiamond,
                Stage.DIAMOND_EXPLORE, Stage.IRON, DIAMOND_BLOCKS);
            case IRON    -> tickOreStage(mc, Items.RAW_IRON, ironStart,
                ChatPilotClient.CONFIG.miningOreQuotaIron,
                Stage.IRON_EXPLORE, Stage.EMERALD, IRON_BLOCKS);
            case EMERALD -> tickOreStage(mc, Items.EMERALD, emeraldStart,
                ChatPilotClient.CONFIG.miningOreQuotaEmerald,
                Stage.EMERALD_EXPLORE, Stage.GOLD, EMERALD_BLOCKS);
            case GOLD    -> tickOreStage(mc, Items.RAW_GOLD, goldStart,
                ChatPilotClient.CONFIG.miningOreQuotaGold,
                Stage.GOLD_EXPLORE, Stage.LAPIS, GOLD_BLOCKS);
            case LAPIS   -> tickOreStage(mc, Items.LAPIS_LAZULI, lapisStart,
                ChatPilotClient.CONFIG.miningOreQuotaLapis,
                Stage.LAPIS_EXPLORE, Stage.COAL, LAPIS_BLOCKS);
            case COAL    -> tickOreStage(mc, Items.COAL, coalStart,
                ChatPilotClient.CONFIG.miningOreQuotaCoal,
                Stage.COAL_EXPLORE, Stage.STONE_FALLBACK, COAL_BLOCKS);

            case EMERALD_EXPLORE,
                 DIAMOND_EXPLORE,
                 GOLD_EXPLORE,
                 IRON_EXPLORE,
                 LAPIS_EXPLORE,
                 COAL_EXPLORE -> tickExploreStage();

            case STONE_FALLBACK -> {
                // Post-rotation ore sweep: keep collecting ANY ore (never plain
                // stone/deepslate) until the mining session's time runs out.
                if (ticksInStage() > STONE_FALLBACK_SECONDS * 20) {
                    enterStage(Stage.DONE);
                    return true;
                }
                if (!ChatPilotClient.BARITONE.isMining()) {
                    ChatPilotClient.BARITONE.mineBlocks(0, ALL_ORE_BLOCKS);
                }
            }
            case DONE -> { return true; }
        }
        return false;
    }
    private void tickGoToStaging(MinecraftClient mc) {
        if (!ChatPilotClient.HOME.hasHome()) {
            startActualMining(mc);
            return;
        }
    
        if (miningStagingPos == null) {
            miningStagingPos = MiningStaging.stagingSurfacePos(
                    mc,
                    ChatPilotClient.CONFIG.miningMinDistanceFromHome
            );
    
            ChatPilotMod.LOGGER.info(
                    "[ChatPilot] Walking to mining staging point {} before digging",
                    miningStagingPos
            );
    
            ChatPilotClient.BARITONE.gotoNear(
                    miningStagingPos,
                    ChatPilotClient.CONFIG.miningStagingArrivalRadius
            );
        }
    
        int radius = Math.max(3, ChatPilotClient.CONFIG.miningStagingArrivalRadius);
    
        if (MiningStaging.isNearXZ(mc.player.getBlockPos(), miningStagingPos, radius)
                || ticksInStage() > 20 * 90) {
            ChatPilotClient.BARITONE.hardReset();
            startActualMining(mc);
        } else if (!ChatPilotClient.BARITONE.isPathing()) {
            ChatPilotClient.BARITONE.gotoNear(miningStagingPos, radius);
        }
    }

    private void startActualMining(MinecraftClient mc) {
        if (mc == null || mc.player == null) {
            enterStage(Stage.DONE);
            return;
        }
    
        chatTarget = null;
    
        if (ChatPilotClient.CONFIG.miningUseChatDemand && ChatPilotClient.ORE_DEMAND != null) {
            chatTarget = ChatPilotClient.ORE_DEMAND.getMostRequestedOre();
    
            if (chatTarget != null) {
                chatTargetStartCount = countOreDrop(mc.player, chatTarget);
    
                ChatPilotMod.LOGGER.info(
                        "[ChatPilot] Chat-selected mining target: {} scores={}",
                        chatTarget.id,
                        ChatPilotClient.ORE_DEMAND.snapshotScores()
                );
    
                enterStage(Stage.CHAT_REQUESTED);
                return;
            }
        }
    
        enterStage(Stage.DIAMOND);
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
            // Quota met - but don't walk away from a half-mined vein. Keep
            // mining while target ore is still within a few blocks; advance
            // only once the immediate area has been cleared out.
            if (!oreBlocksNearby(mc, mineBlocks)) {
                ChatPilotMod.LOGGER.info("[ChatPilot] {} quota met ({}/{}) and area cleared, advancing",
                        stage, got, quota);
                enterStage(nextStage);
                return;
            }
        }
        if (!ChatPilotClient.BARITONE.isMining()) {
            ChatPilotClient.BARITONE.mineBlocks(0, mineBlocks);
        }
        if (clientTick() - lastProgressTick > EXPLORE_AFTER_SECONDS * 20) {
            // No exploring/wandering: the bot stays in one mining area. If an
            // ore is not turning up here, move to the next ore in place rather
            // than walking off to a brand new spot.
            ChatPilotMod.LOGGER.info("[ChatPilot] {} stalled, moving to next ore (staying put)", stage);
            enterStage(nextStage);
        }
    }


    private void tickChatRequestedOreStage(MinecraftClient mc) {
        int got = countOreDrop(mc.player, chatTarget) - chatTargetStartCount;
    
        if (got > lastProgressCount) {
            lastProgressCount = got;
            lastProgressTick = clientTick();
        }
    
        if (got >= chatTarget.defaultQuota && !oreBlocksNearby(mc, chatTarget.blocks)) {
            ChatPilotMod.LOGGER.info(
                    "[ChatPilot] Chat target {} quota met ({}/{}) and area cleared, advancing",
                    chatTarget.id,
                    got,
                    chatTarget.defaultQuota
            );
    
            chatTarget = null;
            enterStage(Stage.DIAMOND);
            return;
        }
    
        if (!ChatPilotClient.BARITONE.isMining()) {
            ChatPilotClient.BARITONE.mineBlocks(0, chatTarget.blocks);
        }
    
        if (clientTick() - lastProgressTick > EXPLORE_AFTER_SECONDS * 20) {
            // No exploring/wandering - switch to the normal in-place rotation.
            ChatPilotMod.LOGGER.info(
                    "[ChatPilot] Chat target {} stalled, switching to normal mining rotation",
                    chatTarget.id
            );
            chatTarget = null;
            enterStage(Stage.DIAMOND);
        }
    }


    

    private void tickExploreStage() {
        if (ticksInStage() > EXPLORE_DURATION_SECONDS * 20) {
            switch (stage) {
                case EMERALD_EXPLORE -> enterStage(Stage.EMERALD);
                case DIAMOND_EXPLORE -> enterStage(Stage.DIAMOND);
                case GOLD_EXPLORE    -> enterStage(Stage.GOLD);
                case IRON_EXPLORE    -> enterStage(Stage.IRON);
                case LAPIS_EXPLORE   -> enterStage(Stage.LAPIS);
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


        if (next == Stage.CHAT_REQUESTED) {
            exploreCycles = 0;
        }
        ChatPilotClient.BARITONE.hardReset();
        switch (next) {
            case CHAT_REQUESTED -> {
                if (chatTarget != null) {
                    ChatPilotClient.BARITONE.mineBlocks(0, chatTarget.blocks);
                } else {
                    enterStage(Stage.DIAMOND);
                }
            }
            case GO_TO_STAGING -> {
                miningStagingPos = MiningStaging.stagingSurfacePos(
                        MinecraftClient.getInstance(),
                        ChatPilotClient.CONFIG.miningMinDistanceFromHome
                );
            
                ChatPilotClient.BARITONE.gotoNear(
                        miningStagingPos,
                        ChatPilotClient.CONFIG.miningStagingArrivalRadius
                );
            }
            
            case CHAT_REQUESTED_EXPLORE -> ChatPilotClient.BARITONE.run("explore");
                
            case EMERALD           -> ChatPilotClient.BARITONE.mineBlocks(0, EMERALD_BLOCKS);
            case DIAMOND           -> ChatPilotClient.BARITONE.mineBlocks(0, DIAMOND_BLOCKS);
            case GOLD              -> ChatPilotClient.BARITONE.mineBlocks(0, GOLD_BLOCKS);
            case IRON              -> ChatPilotClient.BARITONE.mineBlocks(0, IRON_BLOCKS);
            case LAPIS             -> ChatPilotClient.BARITONE.mineBlocks(0, LAPIS_BLOCKS);
            case COAL              -> ChatPilotClient.BARITONE.mineBlocks(0, COAL_BLOCKS);
            case EMERALD_EXPLORE,
                 DIAMOND_EXPLORE,
                 GOLD_EXPLORE,
                 IRON_EXPLORE,
                 LAPIS_EXPLORE,
                 COAL_EXPLORE      -> ChatPilotClient.BARITONE.run("explore");
            case STONE_FALLBACK    -> ChatPilotClient.BARITONE.mineBlocks(0, ALL_ORE_BLOCKS);
            case DONE              -> {}
        }
    }

    private static OreType oreTypeOf(Stage s) {
        return switch (s) {
            case EMERALD, EMERALD_EXPLORE -> OreType.EMERALD;
            case DIAMOND, DIAMOND_EXPLORE -> OreType.DIAMOND;
            case GOLD, GOLD_EXPLORE -> OreType.GOLD;
            case IRON, IRON_EXPLORE -> OreType.IRON;
            case LAPIS, LAPIS_EXPLORE -> OreType.LAPIS;
            case COAL, COAL_EXPLORE -> OreType.COAL;
            default -> OreType.NONE;
        };
    }

    @Override
    public boolean onStuck() {
        ChatPilotMod.LOGGER.info("[ChatPilot] Mining stall recovery from stage {}", stage);
        ChatPilotClient.BARITONE.hardReset();
        switch (stage) {
            case CHAT_REQUESTED, CHAT_REQUESTED_EXPLORE -> {
                advanceOrExplore(Stage.CHAT_REQUESTED_EXPLORE, Stage.DIAMOND);
                return true;
            }

            case GO_TO_STAGING -> {
                startActualMining(MinecraftClient.getInstance());
                return true;
            }
            case DIAMOND, DIAMOND_EXPLORE -> { advanceOrExplore(Stage.DIAMOND_EXPLORE, Stage.IRON);            return true; }
            case IRON,    IRON_EXPLORE    -> { advanceOrExplore(Stage.IRON_EXPLORE,    Stage.EMERALD);         return true; }
            case EMERALD, EMERALD_EXPLORE -> { advanceOrExplore(Stage.EMERALD_EXPLORE, Stage.GOLD);            return true; }
            case GOLD,    GOLD_EXPLORE    -> { advanceOrExplore(Stage.GOLD_EXPLORE,    Stage.LAPIS);           return true; }
            case LAPIS,   LAPIS_EXPLORE   -> { advanceOrExplore(Stage.LAPIS_EXPLORE,   Stage.COAL);            return true; }
            case COAL,    COAL_EXPLORE    -> { advanceOrExplore(Stage.COAL_EXPLORE,    Stage.STONE_FALLBACK);  return true; }
            case STONE_FALLBACK           -> { enterStage(Stage.DONE); return true; }
            case DONE                     -> { return false; }
        }
        return false;
    }

    private void advanceOrExplore(Stage exploreStage, Stage skipStage) {
        // Exploring is disabled - the bot mines one area. On a stall recovery,
        // move straight to the next ore instead of wandering off to a new spot.
        enterStage(skipStage);
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
    private static final String[] DIAMOND_BLOCKS =
        { "diamond_ore", "deepslate_diamond_ore" };
    private static final String[] GOLD_BLOCKS =
        { "gold_ore", "deepslate_gold_ore", "nether_gold_ore" };
    private static final String[] IRON_BLOCKS =
        { "iron_ore", "deepslate_iron_ore" };
    private static final String[] LAPIS_BLOCKS =
        { "lapis_ore", "deepslate_lapis_ore" };
    private static final String[] COAL_BLOCKS =
        { "coal_ore", "deepslate_coal_ore" };

    /** Every ore the bot will mine. Used by the post-rotation ore sweep so it
     *  keeps collecting ores - never plain stone/deepslate - for the rest of
     *  the session. */
    private static final String[] ALL_ORE_BLOCKS = {
        "diamond_ore",  "deepslate_diamond_ore",
        "iron_ore",     "deepslate_iron_ore",
        "gold_ore",     "deepslate_gold_ore", "nether_gold_ore",
        "emerald_ore",  "deepslate_emerald_ore",
        "lapis_ore",    "deepslate_lapis_ore",
        "redstone_ore", "deepslate_redstone_ore",
        "copper_ore",   "deepslate_copper_ore",
        "coal_ore",     "deepslate_coal_ore"
    };

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

    /**
     * True if any of the given ore block ids sits within ORE_FINISH_RADIUS of
     * the player. Used after a quota is met to keep clearing the rest of a
     * vein instead of walking off and leaving ore behind.
     */
    private static boolean oreBlocksNearby(MinecraftClient mc, String[] oreBlocks) {
        if (mc == null || mc.player == null || mc.world == null || oreBlocks == null) {
            return false;
        }

        BlockPos center = mc.player.getBlockPos();

        for (int dx = -ORE_FINISH_RADIUS; dx <= ORE_FINISH_RADIUS; dx++) {
            for (int dy = -ORE_FINISH_RADIUS; dy <= ORE_FINISH_RADIUS; dy++) {
                for (int dz = -ORE_FINISH_RADIUS; dz <= ORE_FINISH_RADIUS; dz++) {
                    String path = net.minecraft.registry.Registries.BLOCK
                            .getId(mc.world.getBlockState(center.add(dx, dy, dz)).getBlock())
                            .getPath();

                    for (String ore : oreBlocks) {
                        if (path.equals(ore)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private static boolean isTooCloseToBedrock(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null || ChatPilotClient.CONFIG == null) {
            return false;
        }

        int safeY = mc.world.getBottomY() + Math.max(6, ChatPilotClient.CONFIG.bedrockSafeYOffset);
        return mc.player.getBlockY() <= safeY;
    }
}
