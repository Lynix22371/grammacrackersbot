package com.grammacrackers.chatpilot.chat;

import com.grammacrackers.chatpilot.ChatPilotClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class OreDemandTracker {
    public enum OreTarget {
        DIAMOND(
                "diamond",
                4,
                new String[]{"diamond", "diamonds", "dia", "dias"},
                new String[]{"diamond_ore", "deepslate_diamond_ore"}
        ),
        IRON(
                "iron",
                24,
                new String[]{"iron", "irons"},
                new String[]{"iron_ore", "deepslate_iron_ore"}
        ),
        GOLD(
                "gold",
                8,
                new String[]{"gold", "golden"},
                new String[]{"gold_ore", "deepslate_gold_ore", "nether_gold_ore"}
        ),
        EMERALD(
                "emerald",
                4,
                new String[]{"emerald", "emeralds"},
                new String[]{"emerald_ore", "deepslate_emerald_ore"}
        ),
        COAL(
                "coal",
                16,
                new String[]{"coal"},
                new String[]{"coal_ore", "deepslate_coal_ore"}
        ),
        REDSTONE(
                "redstone",
                16,
                new String[]{"redstone", "red"},
                new String[]{"redstone_ore", "deepslate_redstone_ore"}
        ),
        LAPIS(
                "lapis",
                12,
                new String[]{"lapis", "lazuli"},
                new String[]{"lapis_ore", "deepslate_lapis_ore"}
        ),
        COPPER(
                "copper",
                24,
                new String[]{"copper"},
                new String[]{"copper_ore", "deepslate_copper_ore"}
        );

        public final String id;
        public final int defaultQuota;
        public final String[] words;
        public final String[] blocks;

        OreTarget(String id, int defaultQuota, String[] words, String[] blocks) {
            this.id = id;
            this.defaultQuota = defaultQuota;
            this.words = words;
            this.blocks = blocks;
        }
    }

    private static class Mention {
        final String author;
        final OreTarget target;
        final long timestampMs;

        Mention(String author, OreTarget target, long timestampMs) {
            this.author = author;
            this.target = target;
            this.timestampMs = timestampMs;
        }
    }

    private final Deque<Mention> mentions = new ArrayDeque<>();

    /**
     * author|ore -> last accepted mention timestamp.
     * Prevents one viewer from spamming the same ore 100 times.
     */
    private final Map<String, Long> lastAcceptedByAuthorOre = new HashMap<>();

    public synchronized void onChatMessage(ChatMessage msg) {
        if (msg == null || msg.text == null) return;

        long now = System.currentTimeMillis();
        OreTarget target = parseOre(msg.text);
        if (target == null) return;

        String author = msg.author == null ? "anon" : msg.author.toLowerCase(Locale.ROOT);
        String key = author + "|" + target.id;

        int cooldownSeconds = Math.max(1, ChatPilotClient.CONFIG.miningChatDemandUserCooldownSeconds);
        long cooldownMs = cooldownSeconds * 1000L;

        Long last = lastAcceptedByAuthorOre.get(key);
        if (last != null && now - last < cooldownMs) {
            return;
        }

        lastAcceptedByAuthorOre.put(key, now);
        mentions.addLast(new Mention(author, target, now));
        pruneOld(now);
    }

    public synchronized OreTarget getMostRequestedOre() {
        long now = System.currentTimeMillis();
        pruneOld(now);

        Map<OreTarget, Integer> scores = new HashMap<>();

        for (Mention m : mentions) {
            scores.merge(m.target, 1, Integer::sum);
        }

        OreTarget best = null;
        int bestScore = 0;

        for (var e : scores.entrySet()) {
            if (e.getValue() > bestScore) {
                best = e.getKey();
                bestScore = e.getValue();
            }
        }

        int minMentions = Math.max(1, ChatPilotClient.CONFIG.miningChatDemandMinMentions);
        if (bestScore < minMentions) return null;

        return best;
    }

    public synchronized Map<String, Integer> snapshotScores() {
        long now = System.currentTimeMillis();
        pruneOld(now);

        Map<String, Integer> out = new HashMap<>();
        for (Mention m : mentions) {
            out.merge(m.target.id, 1, Integer::sum);
        }
        return out;
    }

    private OreTarget parseOre(String raw) {
        String text = raw.toLowerCase(Locale.ROOT);

        for (OreTarget target : OreTarget.values()) {
            for (String word : target.words) {
                if (containsWord(text, word)) {
                    return target;
                }
            }
        }

        return null;
    }

    private boolean containsWord(String text, String word) {
        return text.matches(".*(^|[^a-z0-9])" + java.util.regex.Pattern.quote(word) + "([^a-z0-9]|$).*");
    }

    private void pruneOld(long nowMs) {
        int windowSeconds = Math.max(10, ChatPilotClient.CONFIG.miningChatDemandWindowSeconds);
        long cutoff = nowMs - windowSeconds * 1000L;

        while (!mentions.isEmpty() && mentions.peekFirst().timestampMs < cutoff) {
            mentions.removeFirst();
        }

        lastAcceptedByAuthorOre.entrySet().removeIf(e -> e.getValue() < cutoff);
    }
}
