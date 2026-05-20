package com.grammacrackers.chatpilot.tasks;

public interface Task {
    /** Human readable name shown on HUD. */
    String displayName();

    /** Identifier used for vote keys. */
    String id();

    /** Begin executing. Called once. */
    void start();

    /** Tick. Return true when finished naturally. */
    boolean tick();

    /** The watchdog declared a stall; recover or fail. Return true if recovered. */
    boolean onStuck();

    /** Combat is starting; pause and remember state. */
    void onCombatStart();

    /** Combat finished; resume from where we left off. */
    void onCombatEnd();

    /** Force stop, no recovery. */
    void cancel();

    /**
     * If true, the HUD hides the per-task seconds-remaining display and
     * progress bar for this task. Used by Explore and Mystery, where
     * runtime is bounded by what the bot finds rather than a fixed clock.
     * Default is {@code false} so existing tasks keep their countdown.
     */
    default boolean indefiniteDuration() { return false; }
}
