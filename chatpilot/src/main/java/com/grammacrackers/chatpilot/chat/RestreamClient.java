package com.grammacrackers.chatpilot.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import okhttp3.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Restream Chat WebSocket client.
 *
 * Endpoint: wss://chat.api.restream.io/ws?accessToken=&lt;token&gt;
 *
 * Implements the official Restream Chat API as documented at
 * https://developers.restream.io/chat/getting-started
 *
 * Each frame is a JSON envelope:
 *   { action: "heartbeat" | "connection_info" | "event" | ..., payload: {...}, timestamp: number }
 *
 * Only action == "event" carries chat. Inside that, payload.eventPayload has
 * the per-platform message shape (Twitch, YouTube, Discord, Kick, Trovo, ...).
 * All shapes have a "text" field. The author lives at payload.eventPayload.author
 * with platform-specific name field names (displayName, name, username, nickname).
 *
 * The client autoreconnects with exponential backoff and treats missing
 * heartbeats (60s+ without server frame) as a dead connection.
 */
public class RestreamClient {

    private static final String WS_URL_FMT = "wss://chat.api.restream.io/ws?accessToken=%s";
    private static final long HEARTBEAT_GRACE_MS = 60_000L;

    private OkHttpClient http;
    private WebSocket socket;
    private Consumer<ChatMessage> sink = m -> {};
    private long backoffMs = 2_000L;
    private boolean wantOpen = false;
    private Thread keeper;

    /** Ticks every time we receive any frame from the server, used to detect death. */
    private final AtomicLong lastFrameAtMs = new AtomicLong(0);
    private long messagesReceived = 0;

    public void setMessageSink(Consumer<ChatMessage> sink) { this.sink = sink; }

    public synchronized void start() {
        if (wantOpen) return;
        String token = currentToken();
        if (token == null || token.isBlank()) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Restream] No access token yet. " +
                "Run /restream login in Minecraft to authorize via OAuth, or paste " +
                "a token into config/chatpilot/config.json under restreamAccessToken.");
            return;
        }
        wantOpen = true;
        http = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
        keeper = new Thread(this::keeperLoop, "ChatPilot-Restream-Keeper");
        keeper.setDaemon(true);
        keeper.start();
        ChatPilotMod.LOGGER.info("[ChatPilot][Restream] Keeper thread started");
    }

    /**
     * Called by the OAuth manager when the access token is refreshed. Forces
     * the WebSocket to drop and reconnect with the new credential.
     */
    public synchronized void onTokenRefreshed() {
        if (!wantOpen) {
            // Tokens just appeared for the first time; start now.
            start();
            return;
        }
        if (socket != null) {
            try { socket.close(4001, "token refreshed"); } catch (Exception ignored) {}
        }
    }

    private String currentToken() {
        var auth = com.grammacrackers.chatpilot.ChatPilotClient.AUTH;
        if (auth != null && auth.hasAccessToken()) return auth.getAccessToken();
        return com.grammacrackers.chatpilot.ChatPilotClient.CONFIG.restreamAccessToken;
    }

    public synchronized void stop() {
        wantOpen = false;
        if (socket != null) {
            try { socket.close(1000, "shutdown"); } catch (Exception ignored) {}
            socket = null;
        }
        if (http != null) {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
            http = null;
        }
    }

    private void keeperLoop() {
        while (wantOpen) {
            try {
                connectAndPark();
                if (!wantOpen) return;
                ChatPilotMod.LOGGER.info("[ChatPilot][Restream] reconnecting in {}ms", backoffMs);
                Thread.sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 60_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                ChatPilotMod.LOGGER.warn("[ChatPilot][Restream] keeper threw", t);
            }
        }
    }

    /** Opens a websocket and blocks the keeper thread until the connection dies. */
    private void connectAndPark() throws InterruptedException {
        String token = currentToken();
        if (token == null || token.isBlank()) return;

        Request req = new Request.Builder()
            .url(String.format(WS_URL_FMT, token))
            .build();

        final Object aliveLock = new Object();
        final boolean[] alive = {true};

        socket = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                ChatPilotMod.LOGGER.info("[ChatPilot][Restream] connected: {}", response.code());
                lastFrameAtMs.set(System.currentTimeMillis());
                backoffMs = 2_000L;
            }
            @Override public void onMessage(WebSocket ws, String text) {
                lastFrameAtMs.set(System.currentTimeMillis());
                handleFrame(text);
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                ChatPilotMod.LOGGER.warn("[ChatPilot][Restream] failure: {} (code {})",
                    t.toString(), r == null ? -1 : r.code());
                synchronized (aliveLock) { alive[0] = false; aliveLock.notifyAll(); }
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                ChatPilotMod.LOGGER.info("[ChatPilot][Restream] closed: {} {}", code, reason);
                synchronized (aliveLock) { alive[0] = false; aliveLock.notifyAll(); }
            }
        });

        // Park until the connection dies OR we miss heartbeats
        long lastSeen = System.currentTimeMillis();
        synchronized (aliveLock) {
            while (alive[0] && wantOpen) {
                aliveLock.wait(5_000);
                long now = System.currentTimeMillis();
                long sinceFrame = now - lastFrameAtMs.get();
                if (sinceFrame > HEARTBEAT_GRACE_MS) {
                    ChatPilotMod.LOGGER.warn(
                        "[ChatPilot][Restream] no heartbeat in {}ms, treating connection as dead",
                        sinceFrame);
                    try { socket.close(4000, "heartbeat timeout"); } catch (Exception ignored) {}
                    alive[0] = false;
                }
            }
        }
        socket = null;
    }

    private void handleFrame(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return;
            JsonObject o = root.getAsJsonObject();

            String action = o.has("action") && o.get("action").isJsonPrimitive()
                ? o.get("action").getAsString() : null;
            if (action == null) return;

            // Quietly ignore non-chat actions
            switch (action) {
                case "heartbeat":         return;
                case "connection_info": {
                    JsonObject p = o.has("payload") && o.get("payload").isJsonObject()
                        ? o.getAsJsonObject("payload") : null;
                    String status = p != null && p.has("status") ? p.get("status").getAsString() : "?";
                    String tgt = p != null && p.has("target") && p.get("target").isJsonObject()
                        ? p.getAsJsonObject("target").toString() : "(no target)";
                    ChatPilotMod.LOGGER.info("[ChatPilot][Restream] connection_info status={} target={}",
                        status, tgt);
                    return;
                }
                case "connection_closed": {
                    ChatPilotMod.LOGGER.info("[ChatPilot][Restream] connection_closed: {}", o);
                    return;
                }
                case "event":             break; // fall through to parse
                default:                  return; // reply_*, relay_*: not chat for us
            }

            JsonObject payload = o.has("payload") && o.get("payload").isJsonObject()
                ? o.getAsJsonObject("payload") : null;
            if (payload == null) return;

            int eventTypeId = payload.has("eventTypeId") && payload.get("eventTypeId").isJsonPrimitive()
                ? payload.get("eventTypeId").getAsInt() : -1;
            JsonElement evtEl = payload.get("eventPayload");
            if (evtEl == null || !evtEl.isJsonObject()) return;
            JsonObject ep = evtEl.getAsJsonObject();

            // Skip bot-emitted messages (Restream relay echo, etc.)
            if (ep.has("bot") && ep.get("bot").isJsonPrimitive() && ep.get("bot").getAsBoolean()) {
                return;
            }

            String text = pickString(ep, "text");
            if (text == null || text.isBlank()) return;

            String author = extractAuthor(ep);
            if (author == null || author.isBlank()) author = "viewer";

            messagesReceived++;
            if (messagesReceived <= 5 || messagesReceived % 50 == 0) {
                ChatPilotMod.LOGGER.info("[ChatPilot][Restream] msg #{} type={} author={} text={}",
                    messagesReceived, eventTypeId, author, text);
            }

            sink.accept(new ChatMessage(author, text, System.currentTimeMillis(), "restream"));
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.debug("[ChatPilot][Restream] frame parse error", t);
        }
    }

    /** Try every platform's known author-name field, in order of preference. */
    private static String extractAuthor(JsonObject ep) {
        if (ep.has("author") && ep.get("author").isJsonObject()) {
            JsonObject a = ep.getAsJsonObject("author");
            String name = pickString(a,
                "displayName",  // Twitch, YouTube
                "nickname",     // Discord
                "name",         // Discord, Facebook, LinkedIn, Trovo
                "username",     // Discord, Kick, DLive, Twitch
                "id"            // last-resort identifier
            );
            if (name != null) return name;
        }
        // Some sources put a flat string at "author"
        return pickString(ep, "author", "username", "displayName", "name");
    }

    private static String pickString(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                String s = o.get(k).getAsString();
                if (s != null && !s.isBlank()) return s;
            }
        }
        return null;
    }
}
