package com.grammacrackers.chatpilot.event;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.config.ChatPilotConfig;
import com.grammacrackers.chatpilot.ui.KeybindManager;
import net.minecraft.client.MinecraftClient;

/**
 * One tick callback to drive everything. Order matters:
 *   1. Combat handler runs first so it can interrupt tasks.
 *   2. House protection runs before tasks so a violating Baritone state
 *      is reset before TaskManager's watchdog fires.
 *   3. Tasks tick (state machine, watchdog).
 *   4. Vote manager ticks (drains chat, opens/closes windows).
 *   5. Dance manager ticks (ends the dance window when the timer expires).
 *   6. Chat sources are started lazily once world is loaded.
 */
public class SessionTicker {

    private static boolean chatStarted = false;

    public static void onClientTick(MinecraftClient mc) {
        // Always advance our own clock. Doing this before any early-return
        // means durations anchored on TickClock.now() never freeze.
        TickClock.advance();

        if (mc.world == null || mc.player == null) return;

        // Auto-track home: whenever the player is sleeping in a bed, treat that
        // bed as the new home. This way grandma can rebuild her house, place a
        // new bed, sleep there once, and the bot picks up the move automatically.
        try {
            mc.player.getSleepingPosition().ifPresent(bedPos -> {
                if (ChatPilotClient.HOME == null) return;
                var current = ChatPilotClient.HOME.getBedPos();
                if (current == null || !current.equals(bedPos)) {
                    String dim = mc.world.getRegistryKey().getValue().toString();
                    ChatPilotClient.HOME.setBed(bedPos.toImmutable(), dim);
                }
            });
        } catch (Throwable ignored) {}

        if (!KeybindManager.pilotEnabled) return;

        ensureChatStarted();


        if (ChatPilotClient.LAVA_ESCAPE != null && ChatPilotClient.LAVA_ESCAPE.tick(mc)) {
            ChatPilotClient.VOTES.tick();
            if (ChatPilotClient.DANCE != null) ChatPilotClient.DANCE.tick();
            return;
        }
        
        if (ChatPilotClient.WATER_ESCAPE != null && ChatPilotClient.WATER_ESCAPE.tick(mc)) {
            ChatPilotClient.VOTES.tick();
            if (ChatPilotClient.DANCE != null) ChatPilotClient.DANCE.tick();
            return;
        }
        
        // Combat first
        ChatPilotClient.COMBAT.tick();

        // House protection runs before tasks so a violating Baritone state
        // is reset before TaskManager's watchdog fires.
        ChatPilotClient.HOUSE_GUARD.tick();

        // If combat just ended, the TaskManager.notifyCombatEnd will be triggered
        // from CombatHandler. Then tasks resume. If still in combat pause the task
        // manager will skip its main work this tick.
        ChatPilotClient.TASKS.tick();



        if (ChatPilotClient.LOOK_WALKING != null) {
            ChatPilotClient.LOOK_WALKING.tick(mc);
        }

        if (ChatPilotClient.DOOR_GUARD != null) {
            ChatPilotClient.DOOR_GUARD.tick(mc);
        }
        
        if (ChatPilotClient.UNSTUCK != null) {
            ChatPilotClient.UNSTUCK.tick(mc);
        }
        
        ChatPilotClient.VOTES.tick();

        // Voting always ticks so it can open new windows when idle
        ChatPilotClient.VOTES.tick();

        // Dance manager tracks the timed window for hype dances.
        if (ChatPilotClient.DANCE != null) ChatPilotClient.DANCE.tick();
    }

    private static void ensureChatStarted() {
        if (chatStarted) return;
        chatStarted = true;
        ChatPilotConfig cfg = ChatPilotClient.CONFIG;
        if (cfg.chatSource == ChatPilotConfig.ChatSource.RESTREAM
            || cfg.chatSource == ChatPilotConfig.ChatSource.BOTH) {
            ChatPilotClient.RESTREAM.start();
        }
        if (cfg.chatSource == ChatPilotConfig.ChatSource.YOUTUBE
            || cfg.chatSource == ChatPilotConfig.ChatSource.BOTH) {
            ChatPilotClient.YOUTUBE.start();
        }
    }
}
