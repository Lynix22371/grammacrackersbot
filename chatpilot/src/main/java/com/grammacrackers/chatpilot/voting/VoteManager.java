package com.grammacrackers.chatpilot.voting;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import com.grammacrackers.chatpilot.chat.ChatMessage;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class VoteManager {

    public enum Phase { IDLE, OPEN, CLOSED }

    private final Map<String, VoteOption> options = VoteOption.defaultOptions();
    /**
     * Subset of {@link #options} that are eligible for the CURRENT vote round.
     * Built at vote-open time. Sleep is included only at night, and Mystery
     * is included on every Nth round per config.
     */
    private Map<String, VoteOption> activeOptions = new LinkedHashMap<>();

    private Phase phase = Phase.IDLE;
    private long  windowEndTick;
    private long  windowStartTick;
    private final Map<String, Integer> tally = new LinkedHashMap<>();
    private final Set<String> voters = new HashSet<>();
    private final ConcurrentLinkedQueue<ChatMessage> incoming = new ConcurrentLinkedQueue<>();

    /**
     * Counts vote rounds opened over the lifetime of this game session.
     * Drives the "Mystery every Nth vote" rotation. Reset between Minecraft
     * launches, which is fine; the cadence still alternates from any starting
     * point.
     */
    private int votesOpenedSinceLaunch = 0;

    public Phase getPhase() { return phase; }
    public Map<String, VoteOption> getOptions() { return activeOptions.isEmpty() ? options : activeOptions; }
    public Map<String, VoteOption> getAllOptions() { return options; }
    public Map<String, Integer> getTallySnapshot() { return new LinkedHashMap<>(tally); }
    public int getTotalVotes() { return tally.values().stream().mapToInt(Integer::intValue).sum(); }

    public long getSecondsRemaining() {
        if (phase != Phase.OPEN) return 0;
        long now = clientTick();
        return Math.max(0, (windowEndTick - now) / 20);
    }

    /** Called from background chat threads. Safe to call any time. */
    public void ingestChatMessage(ChatMessage msg) {
        incoming.offer(msg);
    }

    /** Called from the main client tick. */
    public void tick() {
        // Drain incoming chat into tally if we're open
        ChatMessage m;
        while ((m = incoming.poll()) != null) {
            if (phase != Phase.OPEN) continue;
            String pick = parseVote(m.text);
            if (pick == null) continue;
            if (voters.add(m.author)) {
                tally.merge(pick, 1, Integer::sum);
            }
        }

        if (phase == Phase.OPEN) {
            if (clientTick() >= windowEndTick) {
                closeAndStartWinner();
            }
        } else if (phase == Phase.IDLE) {
            // Auto-open a new vote whenever the task manager is idle.
            // Dance-mode is a separate phase that suspends the task system
            // entirely, so this still parks safely while the bot is dancing.
            if (ChatPilotClient.TASKS.getPhase() == com.grammacrackers.chatpilot.tasks.TaskManager.Phase.IDLE) {
                openVote();
            }
        }
    }

    public void openVote() {
        phase = Phase.OPEN;
        windowStartTick = clientTick();
        windowEndTick = windowStartTick + ChatPilotClient.CONFIG.voteWindowSeconds * 20L;
        tally.clear();
        voters.clear();
        votesOpenedSinceLaunch++;



        // Build the active option set for THIS round.
        activeOptions = new LinkedHashMap<>();

        activeOptions.put("1", options.get("1"));
        activeOptions.put("2", options.get("2"));
        activeOptions.put("3", options.get("3"));
        activeOptions.put("4", options.get("4")); // Farm flint
        
        boolean night = isNightTime();
        boolean offerMyst = shouldOfferMystery();
        
        int nextSlot = 5;
        if (ChatPilotClient.UNSTUCK != null) {
            ChatPilotClient.UNSTUCK.onVoteOpened();
        }
        
        if (night) {
            String key = String.valueOf(nextSlot++);
            activeOptions.put(key, VoteOption.buildSleep(key));
        }
        
        if (offerMyst) {
            String key = String.valueOf(nextSlot++);
            activeOptions.put(key, VoteOption.buildMystery(key));
        }
        
        if (night && offerMyst) {
            ChatPilotMod.LOGGER.info("[ChatPilot] Vote opened (night + mystery) for {}s", ChatPilotClient.CONFIG.voteWindowSeconds);
        } else if (night) {
            ChatPilotMod.LOGGER.info("[ChatPilot] Vote opened (night, sleep available) for {}s", ChatPilotClient.CONFIG.voteWindowSeconds);
        } else if (offerMyst) {
            ChatPilotMod.LOGGER.info("[ChatPilot] Vote opened (mystery available) for {}s", ChatPilotClient.CONFIG.voteWindowSeconds);
        } else {
            ChatPilotMod.LOGGER.info("[ChatPilot] Vote opened for {}s", ChatPilotClient.CONFIG.voteWindowSeconds);
        }
        if (ChatPilotClient.UNSTUCK != null && ChatPilotClient.UNSTUCK.shouldOfferUnstuck()) {
            String key = nextFreeVoteKey();
            activeOptions.put(key, VoteOption.buildUnstuck(key));
        
            ChatPilotMod.LOGGER.warn(
                    "[ChatPilot] Unstuck vote option added: {}",
                    ChatPilotClient.UNSTUCK.debugSummary()
            );
        }
        
        for (String k : activeOptions.keySet()) {
            tally.put(k, 0);
        }
    }

    /** True if a Mystery slot should be added to this vote round. */
    private boolean shouldOfferMystery() {
        int n = Math.max(1, ChatPilotClient.CONFIG.mysteryEveryNVotes);
        return (votesOpenedSinceLaunch % n) == 0;
    }
    private String nextFreeVoteKey() {
        for (int i = 1; i <= 9; i++) {
            String key = String.valueOf(i);
    
            if (!activeOptions.containsKey(key)) {
                return key;
            }
        }
    
        return "9";
    }

    /** True if the world's daytime is in the night window. */
    private static boolean isNightTime() {
        try {
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc.world == null) return false;
            long t = mc.world.getTimeOfDay() % 24000L;
            return t >= 13000L && t < 23000L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void closeAndStartWinner() {
        phase = Phase.CLOSED;
        String winner = pickWinner();
        if (winner == null || tally.getOrDefault(winner, 0) < ChatPilotClient.CONFIG.minVotesToStart) {
            ChatPilotMod.LOGGER.info("[ChatPilot] No quorum, defaulting to mining");
            winner = "1";
        }
        VoteOption chosen = activeOptions.getOrDefault(winner, options.get(winner));
        if (chosen == null) chosen = options.get("1"); // last-resort safety net
        ChatPilotMod.LOGGER.info("[ChatPilot] Winner: {} ({} votes of {})",
            chosen.label, tally.get(winner), getTotalVotes());

        var task = chosen.taskFactory.get();
        int dur = computeDuration(task);
        ChatPilotClient.TASKS.start(task, dur);
        phase = Phase.IDLE;
    }

    /**
     * Scale duration with engagement, but give Explore and Mystery a much
     * longer ceiling because they're indefinite-by-design (they end when
     * the bot finds and loots a structure, not when a clock runs out).
     */
    private int computeDuration(com.grammacrackers.chatpilot.tasks.Task task) {
        if (task != null && task.indefiniteDuration()) {
            return Math.max(ChatPilotClient.CONFIG.minTaskDurationSeconds,
                            ChatPilotClient.CONFIG.indefiniteTaskMaxSeconds);
        }
        int min = ChatPilotClient.CONFIG.minTaskDurationSeconds;
        int max = ChatPilotClient.CONFIG.maxTaskDurationSeconds;
        int votes = getTotalVotes();
        int extra = Math.min(max - min, votes * 2);
        return min + extra;
    }

    private String pickWinner() {
        String best = null;
        int bestN = -1;
        for (var e : tally.entrySet()) {
            if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    /** Friendlier alias map: natural words map to a vote key. */
    private static final java.util.Map<String, String> ALIASES;
    static {
        ALIASES = new java.util.HashMap<>();

        for (String w : new String[]{
                "flint",
                "gravel",
                "arrow",
                "arrows",
                "feather",
                "feathers",
                "farmflint",
                "farm",
                "grind"
        }) {
            ALIASES.put(w, "flint");
        }
        for (String w : new String[]{
                "unstuck",
                "stuck",
                "reset",
                "home",
                "return",
                "save",
                "help",
                "escape"
        }) {
            ALIASES.put(w, "unstuck");
        }
        
        // Mining option
        for (String w : new String[]{"mine","mining","ore","ores","rocks","rock","dig","digging","pick","pickaxe","cave",
                                     "emerald","emeralds","gold","coal"})
            ALIASES.put(w, "mine");
        // Fishing option (replaces wood as of v1.2.0). Keep old wood words
        // mapped to fish so anyone shouting "chop" or "wood" still casts a
        // vote that's actually counted; this avoids dead votes during the
        // transition period.
        for (String w : new String[]{"fish","fishing","rod","cast","catch","reel","bobber","fisher",
                                     "wood","chop","chopping","tree","trees","log","logs","timber","axe"})
            ALIASES.put(w, "fish");
        // Explore option (renamed from Forage in v1.1.0; still accepts the old words)
        for (String w : new String[]{"explore","exploring","exploration","adventure","wander","walk","stroll","outside",
                                     "forage","foraging","gather","gathering","berries","berry","flowers","flower","mushroom","mushrooms",
                                     "pumpkin","pumpkins","sapling","saplings","village","temple","portal"})
            ALIASES.put(w, "explore");
        // Sleep option (only counted when option 4 is sleep)
        for (String w : new String[]{"sleep","sleeping","bed","goodnight","gn","night","rest","nap","zzz"})
            ALIASES.put(w, "sleep");
        // Mystery option (could be slot 4 or 5 this round; resolved by id)
        for (String w : new String[]{"mystery","unknown","surprise","secret","battle","danger","fight","?"})
            ALIASES.put(w, "mystery");
    }

    /**
     * Accept "1", "1!", "vote 1", "#1", "mine", "trees", "explore",
     * "mystery", etc. The numeric path is the fast lane; word matches
     * resolve to a task ID first, then the active option whose factory
     * produces that ID claims the vote.
     */
    public String parseVote(String text) {
        if (text == null) return null;
        String t = text.trim().toLowerCase();
        if (t.isEmpty()) return null;
        // Quick wins on numeric keys (only for ACTIVE options this round)
        for (String k : activeOptions.keySet()) {
            if (t.equals(k)) return k;
            if (t.startsWith(k + " ") || t.startsWith(k + "!") || t.startsWith(k + ".")) return k;
            if (t.equals("#" + k) || t.equals("!" + k)) return k;
            if (t.startsWith("vote " + k)) return k;
            if (t.startsWith("option " + k)) return k;
        }
        // Word match against label first word of any ACTIVE option
        for (var opt : activeOptions.values()) {
            String first = opt.label.split(" ")[0].toLowerCase();
            if (t.equals(first) || t.startsWith(first + " ")) return opt.key;
        }
        // Natural word aliases. Token -> id ("mine"/"wood"/"explore"/"sleep"/"mystery")
        // -> active option key matching that id.
        for (String word : t.split("[^a-z?]+")) {
            if (word.isEmpty()) continue;
            String hit = ALIASES.get(word);
            if (hit == null) continue;
            String key = keyForId(hit);
            if (key != null) return key;
        }
        return null;
    }

    /** Find the active option whose task factory's id matches {@code id}. */
    private String keyForId(String id) {
        // Cheaper than instantiating the factory: hard-code which IDs come
        // from which fixed slots, and special-case mystery by label since
        // it shifts between slot 4 and 5 round to round.
        switch (id) {
            case "mine":    return activeOptions.containsKey("1") ? "1" : null;
            case "fish":    return activeOptions.containsKey("2") ? "2" : null;
            case "explore": return activeOptions.containsKey("3") ? "3" : null;
            case "flint":   return activeOptions.containsKey("4") ? "4" : null;
            case "sleep": {
                for (var e : activeOptions.entrySet()) {
                    if ("Sleep".equalsIgnoreCase(e.getValue().label)) {
                        return e.getKey();
                    }
                }
                return null;
            }
            case "mystery": {
                // Mystery slot is whichever active option's label is "Mystery".
                for (var e : activeOptions.entrySet()) {
                    if ("Mystery".equalsIgnoreCase(e.getValue().label)) return e.getKey();
                }
                return null;
            }
            case "unstuck": {
                for (var e : activeOptions.entrySet()) {
                    if ("Unstuck".equalsIgnoreCase(e.getValue().label)) {
                        return e.getKey();
                    }
                }
                return null;
            }
            default: return null;
        }
    }

    private static long clientTick() {
        return com.grammacrackers.chatpilot.event.TickClock.now();
    }
}
