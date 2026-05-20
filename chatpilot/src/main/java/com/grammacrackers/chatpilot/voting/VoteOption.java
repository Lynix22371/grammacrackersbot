package com.grammacrackers.chatpilot.voting;

import com.grammacrackers.chatpilot.tasks.ExploreTask;
import com.grammacrackers.chatpilot.tasks.FishingTask;
import com.grammacrackers.chatpilot.tasks.MiningTask;
import com.grammacrackers.chatpilot.tasks.MysteryTask;
import com.grammacrackers.chatpilot.tasks.SleepTask;
import com.grammacrackers.chatpilot.tasks.Task;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Vote options chat can pick from. v1.2.0 wiring:
 *   1 - Mine ores       (always offered)  -> emerald/gold/coal mining
 *   2 - Fish            (always offered)  -> replaced wood as of v1.2.0
 *   3 - Explore         (always offered)
 *   4 - Sleep           (only at night)
 *   M - Mystery         (every Nth round; slots into 4 or 5 depending on Sleep)
 *
 * The wood-gathering task still exists in the codebase ({@link
 * com.grammacrackers.chatpilot.tasks.WoodGatheringTask}) but is no longer
 * wired to a slot. If you need to bring wood back, swap the slot 2 factory
 * below from {@code FishingTask::new} back to {@code WoodGatheringTask::new}
 * and update the chat aliases in VoteManager to match.
 */
public class VoteOption {
    public final String key;
    public final String label;
    public final String icon;
    public final int    accentColor;
    public final Supplier<Task> taskFactory;

    public VoteOption(String key, String label, String icon, int accentColor, Supplier<Task> taskFactory) {
        this.key = key;
        this.label = label;
        this.icon = icon;
        this.accentColor = accentColor;
        this.taskFactory = taskFactory;
    }

    public static Map<String, VoteOption> defaultOptions() {
        Map<String, VoteOption> m = new LinkedHashMap<>();
        // Pickaxe glyph for mining
        m.put("1", new VoteOption("1", "Mine ores",  "\u26CF", 0xFFFFC857, MiningTask::new));
        // Fish glyph for fishing
        m.put("2", new VoteOption("2", "Fish",       "\u2698", 0xFF7DD3FC, FishingTask::new));
        // Compass-ish glyph for exploring
        m.put("3", new VoteOption("3", "Explore",    "\u2693", 0xFFFF7AB6, ExploreTask::new));
        // Crescent moon glyph for sleeping (only included when night)
        m.put("4", new VoteOption("4", "Sleep",      "\u263E", 0xFF87A2FF, SleepTask::new));
        return m;
    }

    public static VoteOption buildMystery(String key) {
        return new VoteOption(key, "Mystery",      "?",      0xFFC084FC, MysteryTask::new);
    }
}
