package com.grammacrackers.chatpilot;

import com.grammacrackers.chatpilot.baritone.BaritoneController;
import com.grammacrackers.chatpilot.baritone.StuckDetector;
import com.grammacrackers.chatpilot.chat.RestreamClient;
import com.grammacrackers.chatpilot.chat.YouTubeChatPoller;
import com.grammacrackers.chatpilot.chat.oauth.RestreamAuthManager;
import com.grammacrackers.chatpilot.chat.oauth.RestreamOAuthCallbackServer;
import com.grammacrackers.chatpilot.combat.CombatHandler;
import com.grammacrackers.chatpilot.commands.CommandRegistry;
import com.grammacrackers.chatpilot.config.ChatPilotConfig;
import com.grammacrackers.chatpilot.dance.DanceManager;
import com.grammacrackers.chatpilot.event.SessionTicker;
import com.grammacrackers.chatpilot.explore.VisitedStructuresManager;
import com.grammacrackers.chatpilot.home.HomeManager;
import com.grammacrackers.chatpilot.home.HouseProtectionGuard;
import com.grammacrackers.chatpilot.tasks.TaskManager;
import com.grammacrackers.chatpilot.ui.HudOverlay;
import com.grammacrackers.chatpilot.ui.KeybindManager;
import com.grammacrackers.chatpilot.voting.VoteManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import com.grammacrackers.chatpilot.safety.LavaEscapeManager;
import com.grammacrackers.chatpilot.chat.OreDemandTracker;

public class ChatPilotClient implements ClientModInitializer {

    public static ChatPilotConfig CONFIG;
    public static HomeManager HOME;
    public static HouseProtectionGuard HOUSE_GUARD;
    public static BaritoneController BARITONE;
    public static StuckDetector STUCK;
    public static CombatHandler COMBAT;
    public static TaskManager TASKS;
    public static VoteManager VOTES;
    public static HudOverlay HUD;
    public static RestreamClient RESTREAM;
    public static YouTubeChatPoller YOUTUBE;
    public static RestreamAuthManager AUTH;
    public static RestreamOAuthCallbackServer OAUTH_SERVER;
    public static LavaEscapeManager LAVA_ESCAPE;
    public static OreDemandTracker ORE_DEMAND;

    /** v1.1.0: persistent record of explored structures so Explore/Mystery never repeat. */
    public static VisitedStructuresManager VISITED;

    /** v1.1.0: hype-dance trigger that listens for tips and emits emote + music. */
    public static DanceManager DANCE;

    @Override
    public void onInitializeClient() {
        ChatPilotMod.LOGGER.info("[ChatPilot] Client init starting...");

        CONFIG    = ChatPilotConfig.loadOrCreate();
        HOME      = new HomeManager();
        HOUSE_GUARD = new HouseProtectionGuard();
        BARITONE  = new BaritoneController();
        STUCK     = new StuckDetector();
        COMBAT    = new CombatHandler();
        TASKS     = new TaskManager();
        VOTES     = new VoteManager();
        HUD       = new HudOverlay();
        RESTREAM  = new RestreamClient();
        YOUTUBE   = new YouTubeChatPoller();
        VISITED   = new VisitedStructuresManager();
        DANCE     = new DanceManager();
        LAVA_ESCAPE = new LavaEscapeManager();
        ORE_DEMAND = new OreDemandTracker();

        // OAuth: load credentials/tokens; start refresh loop; wire token notifications.
        AUTH = new RestreamAuthManager();
        AUTH.load();
        AUTH.setOnAccessTokenChanged(t -> RESTREAM.onTokenRefreshed());
        AUTH.startAutoRefresh();
        OAUTH_SERVER = new RestreamOAuthCallbackServer(8765, AUTH);

        KeybindManager.register();
        CommandRegistry.register();

        // Master tick: drives state machines, watchdogs, and chat polling.
        ClientTickEvents.END_CLIENT_TICK.register(SessionTicker::onClientTick);

        // HUD overlay
        HudRenderCallback.EVENT.register(HUD::render);

        // Hook chat sources to the vote manager AND to the dance tracker.
        // The dance tracker reads tipUsd off each message; non-tipping
        // messages are no-ops, so this is cheap.
        RESTREAM.setMessageSink(msg -> {
            VOTES.ingestChatMessage(msg);
            DANCE.onChatMessage(msg);
            ORE_DEMAND.onChatMessage(msg);
        });

        YOUTUBE.setMessageSink(msg -> {
            VOTES.ingestChatMessage(msg);
            DANCE.onChatMessage(msg);
            ORE_DEMAND.onChatMessage(msg);
        });

        ChatPilotMod.LOGGER.info("[ChatPilot] Client init complete (v1.2.1).");
    }
}
