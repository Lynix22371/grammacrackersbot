package com.grammacrackers.chatpilot.event;

/**
 * Monotonic tick counter for ChatPilot's internal scheduling.
 *
 * Why this exists: we previously used {@code MinecraftClient.player.getWorld().getTime()}
 * as our tick source, which has two failure modes:
 *   1) When the player object is briefly null (dimension change, respawn,
 *      world load), we returned 0 as a fallback. This made any duration
 *      computed by subtraction blow up to the world's full age.
 *   2) Across dimensions, world time is not the same value, so durations
 *      anchored before a portal would jump.
 *
 * This counter is incremented exactly once per client tick from {@link SessionTicker}.
 * It only advances forward, never resets, never depends on world or player state.
 */
public final class TickClock {
    private static long ticks = 0L;

    private TickClock() {}

    public static long now() { return ticks; }

    /** Called from SessionTicker on every client tick. */
    static void advance() { ticks++; }
}
