package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.explore.StructureMarker;

/**
 * Mystery task. Same flow as {@link ExploreTask} but targets the
 * "dangerous" structure pool: trial chambers and the deep dark / ancient
 * city. Combat is expected. The display label is intentionally vague so
 * chat sees "Heading towards mystery..." with no spoilers about what
 * structure was rolled.
 *
 * Why this is just a thin subclass: the mechanics of finding a marker,
 * approaching it, walking around it, looting chests, and going home are
 * identical. The only differences are the marker pool (drives Baritone's
 * scan target) and the displayed name. Keeping this in one task class
 * means fewer copies of the chest-loot flow to maintain.
 */
public class MysteryTask extends ExploreTask {

    public MysteryTask() {
        // The label is the only thing chat sees; it matches the user's
        // requested wording. Internal logs still include the actual
        // structure type for debugging.
        super(StructureMarker.Mode.MYSTERY, "Heading towards mystery...");
    }

    @Override public String id() { return "mystery"; }
}
