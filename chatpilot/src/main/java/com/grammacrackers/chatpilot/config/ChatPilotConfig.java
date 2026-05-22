package com.grammacrackers.chatpilot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ChatPilotConfig {

    // === Streaming integration ===
    public ChatSource chatSource = ChatSource.RESTREAM;
    public String restreamAccessToken = "";
    public String youtubeApiKey = "";
    public String youtubeLiveChatId = "";
    public String youtubeBroadcastId = "";
    public int    youtubePollIntervalMs = 4000;

    // === Unstuck voting ===
    public boolean unstuckVoteEnabled = true;
    public int unstuckVoteLookbackVotes = 3;
    public double unstuckVoteMinDistanceBlocks = 10.0;
    public int unstuckReturnTaskDurationSeconds = 30;

    // === Voting ===
    public int    voteWindowSeconds = 30;
    public int    minVotesToStart = 1;
    public int    minTaskDurationSeconds = 120;
    public int    maxTaskDurationSeconds = 300;

    public int    mysteryEveryNVotes = 2;
    public int    indefiniteTaskMaxSeconds = 480;

    // === Behaviour ===
    public boolean enableHungerImmunity = true;
    public boolean protectToolDurability = true;

    public boolean cancelDrowningDamage    = true;
    public boolean cancelFallDamage        = true;
    public boolean cancelSuffocationDamage = true;
    public double  lavaDamageMultiplier    = 0.10;

    // === Camera / stream presentation ===
    public boolean lookWhereWalking = true;
    
    /**
     * Visible camera turn speed while Baritone is simply walking.
     * Higher = snappier, lower = smoother.
     */
    public double lookWhereWalkingMaxYawPerTick = 7.0;
    public double lookWhereWalkingMaxPitchPerTick = 3.0;
    
    /**
     * Slightly downward looks more natural on stream than pitch=0.
     * Use 0.0 if you want it to look exactly horizontally.
     */
    public double lookWhereWalkingPitch = 8.0;
    
    /**
     * Ignore tiny jitter when the player is not really moving.
     */
    public double lookWhereWalkingMinSpeed = 0.015;
    

    // === HUD layout ===
    public String hudAnchor = "CENTER";
    public int    hudOffsetX = 12;
    public int    hudOffsetY = 8;
    public float  hudScale = 1.0f;
    public double  damageTakenMultiplier = 0.25;
    public double  damageDealtMultiplier = 2.0;
    public double  hostileSpawnMultiplier = 0.15;
    public int     hostileSpawnFreeRadius = 48;
    public double  dropMultiplier = 2.0;

    // === Home / protection ===
    public int houseProtectionRadius = 25;
    public int chestSearchRadius = 12;
    
    /**
     * Mining should not start right next to the house.
     * The bot first walks this far from the bed, then starts Baritone mining.
     */
    public int miningMinDistanceFromHome = 100;
    
    /**
     * When returning from underground mining, the bot first exits near this
     * outside mining point, then walks home on the surface.
     */
    public int miningReturnExitDistanceFromHome = 100;
    
    /**
     * Direction of the mining staging point from home.
     * 0 = south/+Z, 90 = west/-X, 180 = north/-Z, 270 = east/+X.
     * Change this if one side of the house is safer.
     */
    public double miningStagingBearingDegrees = 150.0;
    
    /**
     * How close the bot must get to the mining staging point before starting mining.
     */
    public int miningStagingArrivalRadius = 6;

    // === Water escape ===
    public boolean waterEscapeEnabled = true;
    
    /**
     * How long the bot may keep its head underwater before emergency swimming starts.
     * 60 ticks = 3 seconds.
     */
    public int waterEscapeSubmergedTicks = 600;
    
    /**
     * Start emergency escape immediately if air drops below this.
     * Vanilla max air is 300 ticks.
     */
    public int waterEscapeStartAirTicks = 300;
    
    /**
     * Radius used to look for nearby water surface/air.
     */
    public int waterEscapeSurfaceSearchRadius = 16;
    
    /**
     * Vertical scan height above the player.
     */
    public int waterEscapeSurfaceSearchHeight = 48;
    
    /**
     * After this many escape ticks, also try Baritone's surface command once.
     */
    public int waterEscapeBaritoneSurfaceAfterTicks = 20 * 12;

    // === Reliability ===
    public int    stuckThresholdTicks = 300;
    public int    softRestartCooldownTicks = 100;
    public int    combatResumeDelayTicks = 40;
    public int    maxConsecutiveStuckRecoveries = 5;

    // === Mining task targets ===
    // v1.2.0 refocus: mining now targets emerald primarily (rare and exciting),
    // followed by gold (moderate), and coal (common and reliable). Iron, copper,
    // lapis, and diamond are no longer actively pursued. Baritone will still
    // pick up incidental drops as it passes through stone.
    //
    // Why: at the previous coal/copper/lapis/gold rate the bot was outpacing
    // the chest's capacity for those materials and the trash cactus was
    // overflowing. Lower active quotas + emerald hunt = less inventory churn,
    // more "is she gonna find one this time?" excitement on stream.
    public int    miningOreQuotaEmerald = 4;
    public int    miningOreQuotaGold    = 8;
    public int    miningOreQuotaCoal    = 16;

    /** Legacy fields kept so older configs keep parsing cleanly. Unused. */
    public int    miningOreQuotaCopper  = 0;
    public int    miningOreQuotaLapis   = 0;
    public int    miningOreQuotaIron    = 0;

    // === Chat-driven mining ===
    public boolean miningUseChatDemand = true;
    public int miningChatDemandWindowSeconds = 120;
    public int miningChatDemandMinMentions = 2;
    public int miningChatDemandUserCooldownSeconds = 8;


    // === Flint farming ===
    public int flintTargetCount = 32;
    public int flintGravelBatchSize = 64;
    public int flintMinGravelBeforeCycle = 24;
    public int flintTowerHeight = 4;
    public int flintBuildHotbarSlot = 7;
    public int flintToolHotbarSlot = 0;
    public int flintCollectTimeoutTicks = 20 * 90;
    public int flintMineCycleTimeoutTicks = 20 * 45;



    // === Flint / gravel retention ===
    public boolean keepGravelForFlintTask = true;
    
    // === Combat weapons ===
    public boolean combatUseBestWeapon = true;
    public int combatWeaponHotbarSlot = 0;
    public boolean combatPreferAxeOnTie = false;
    


    // === Wood task target (legacy; vote slot 2 is Fishing in v1.2.0) ===
    public int    woodLogQuota = 32;

    // === Bee safety ===
    /**
     * Distance in blocks at which the wood-gathering task bails out of a tree
     * if a beehive or bee_nest is detected. Bees aggro hard if disturbed and
     * a swarm can wreck a 24/7 stream. Default 6 blocks gives plenty of
     * margin for path planning to avoid the affected tree entirely.
     */
    public int    beehiveAvoidanceRadius = 6;

    // === Fishing task ===
    /**
     * Catch target before the fishing task ends naturally. Each catch
     * triggers a single-item drop; loot is intentionally not a primary
     * resource source so the target is low and feels like a complete cycle.
     */
    public int    fishingCatchTarget       = 8;
    public int    fishingWaterScanRadius   = 16;
    /** Recast if no bite in this many ticks. Vanilla average is ~10s; we wait 30s for safety. */
    public int    fishingMaxWaitTicks      = 30 * 20;
    public int    fishingSettleTicks       = 14;
    /** Velocity Y threshold below which we register a bite. Negative = bobber dipped. */
    public double fishingBiteVelocityY     = -0.04;

    // === Dance / hype rewards ===
    public double danceJewelThresholdUsd = 5.0;
    public int    danceDurationSeconds   = 15;
    public String danceCommand           = "/emote dance";
    public String danceMusicSound        = "minecraft:music_disc.cat";
    public boolean danceUseThirdPerson   = true;

    // === Mystery boat travel ===
    public boolean mysteryUseBoat = true;
    public boolean mysteryCraftBoatIfMissing = true;
    
    /**
     * Only use the boat when the target is far enough away.
     * Prevents stupid boat placement for tiny puddles near the structure.
     */
    public int mysteryBoatMinTravelDistance = 80;
    
    /**
     * How many blocks ahead to scan for usable water.
     */
    public int mysteryBoatWaterLookahead = 10;
    
    /**
     * Do not waste the whole mystery run trying to craft.
     */
    public int mysteryBoatPrepTimeoutTicks = 20 * 20;

    // === Trash cactus ===
    /**
     * Items dropped onto the cactus during the return-home flow. The bot
     * walks to the configured cactus position before depositing in the
     * hopper, faces the cactus, and throws each matching stack at it.
     * Cactus blocks destroy items that touch them, so this is the cleanest
     * way to keep cobblestone/dirt/etc out of the chest without filling
     * inventory with useless drops.
     *
     * Note: the bot's sword/pickaxe/axe/etc are protected by
     * {@code protectToolDurability} and the deposit logic's tool check, so
     * they never end up here even if you accidentally added an item id.
     */
    public List<String> trashItemIds = new ArrayList<>(Arrays.asList(
        "minecraft:cobblestone",
        "minecraft:cobbled_deepslate",
        "minecraft:dirt",
        "minecraft:coarse_dirt",
        "minecraft:rooted_dirt",
        "minecraft:grass_block",
        "minecraft:tuff",
        "minecraft:sand",
        "minecraft:red_sand",
        "minecraft:raw_copper",
        "minecraft:copper_ingot",
        "minecraft:lapis_lazuli",
        "minecraft:andesite",
        "minecraft:diorite",
        "minecraft:granite",
        "minecraft:wheat_seeds",
        "minecraft:melon_seeds",
        "minecraft:pumpkin_seeds",
        "minecraft:beetroot_seeds",
        "minecraft:torchflower_seeds",
        "minecraft:pitcher_pod"
    ));

    public enum ChatSource { RESTREAM, YOUTUBE, BOTH, OFF }

    public static ChatPilotConfig loadOrCreate() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(ChatPilotMod.MOD_ID);
        Path file = dir.resolve("config.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            if (Files.exists(file)) {
                String json = Files.readString(file);
                ChatPilotConfig cfg = gson.fromJson(json, ChatPilotConfig.class);
                if (cfg != null) {
                    if (cfg.trashItemIds == null || cfg.trashItemIds.isEmpty()) {
                        cfg.trashItemIds = new ChatPilotConfig().trashItemIds;
                    }
                    Files.writeString(file, gson.toJson(cfg));
                    return cfg;
                }
            }
            ChatPilotConfig fresh = new ChatPilotConfig();
            Files.writeString(file, gson.toJson(fresh));
            return fresh;
        } catch (IOException e) {
            ChatPilotMod.LOGGER.error("[ChatPilot] Config load failed, using defaults", e);
            return new ChatPilotConfig();
        }
    }

    public void save() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(ChatPilotMod.MOD_ID);
        Path file = dir.resolve("config.json");
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(this));
        } catch (IOException e) {
            ChatPilotMod.LOGGER.error("[ChatPilot] Config save failed", e);
        }
    }
}
