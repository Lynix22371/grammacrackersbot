package com.grammacrackers.chatpilot.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Polls the YouTube Data API v3 liveChatMessages endpoint. Acts as a fallback
 * or supplement to Restream. Quota-conscious: respects pollingIntervalMillis
 * from the API response and won't poll faster than the configured floor.
 *
 * v1.1.0 also extracts Super Chat / Super Sticker USD amounts when present
 * and attaches them to the {@link ChatMessage}. The DanceManager listens
 * for these and triggers a hype dance when accumulated dollars cross the
 * configured threshold. YouTube's "Jewels" gifting itself does not surface
 * in the Live Chat API yet — only Super Chat / Super Sticker do — so for
 * proper Jewel hookups the user can pipe events through the
 * {@code /jewels add} client command from any external alert tool.
 */
public class YouTubeChatPoller {

    private final OkHttpClient http = new OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build();

    private ScheduledExecutorService exec;
    private Consumer<ChatMessage> sink = m -> {};
    private String nextPageToken;
    private long   serverPollIntervalMs = 0;
    private boolean running = false;

    public void setMessageSink(Consumer<ChatMessage> sink) { this.sink = sink; }

    public synchronized void start() {
        if (running) return;
        if (ChatPilotClient.CONFIG.youtubeApiKey.isBlank()) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] YouTube API key empty, not polling");
            return;
        }
        if (ChatPilotClient.CONFIG.youtubeLiveChatId.isBlank()) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] YouTube liveChatId empty, not polling");
            return;
        }
        running = true;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChatPilot-YT-Poll");
            t.setDaemon(true);
            return t;
        });
        scheduleNext(0);
    }

    public synchronized void stop() {
        running = false;
        if (exec != null) exec.shutdownNow();
        exec = null;
    }

    private void scheduleNext(long delayMs) {
        if (!running || exec == null) return;
        exec.schedule(this::pollOnce, delayMs, TimeUnit.MILLISECONDS);
    }

    private void pollOnce() {
        long minDelay = ChatPilotClient.CONFIG.youtubePollIntervalMs;
        try {
            String url = "https://www.googleapis.com/youtube/v3/liveChat/messages"
                + "?liveChatId=" + ChatPilotClient.CONFIG.youtubeLiveChatId
                + "&part=snippet,authorDetails"
                + "&maxResults=200"
                + "&key=" + ChatPilotClient.CONFIG.youtubeApiKey
                + (nextPageToken != null ? "&pageToken=" + nextPageToken : "");
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    ChatPilotMod.LOGGER.warn("[ChatPilot] YouTube poll HTTP {}", resp.code());
                    scheduleNext(Math.max(minDelay, 10_000L));
                    return;
                }
                String body = resp.body() == null ? "" : resp.body().string();
                handleBody(body);
            }
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] YouTube poll error: {}", t.toString());
        } finally {
            long actual = Math.max(minDelay, serverPollIntervalMs);
            scheduleNext(actual);
        }
    }

    private void handleBody(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return;
        JsonObject o = root.getAsJsonObject();
        if (o.has("nextPageToken")) nextPageToken = o.get("nextPageToken").getAsString();
        if (o.has("pollingIntervalMillis")) serverPollIntervalMs = o.get("pollingIntervalMillis").getAsLong();
        if (!o.has("items") || !o.get("items").isJsonArray()) return;
        JsonArray items = o.getAsJsonArray("items");
        for (JsonElement el : items) {
            if (!el.isJsonObject()) continue;
            JsonObject item = el.getAsJsonObject();
            JsonObject snippet = item.has("snippet") && item.get("snippet").isJsonObject()
                ? item.getAsJsonObject("snippet") : null;
            JsonObject author = item.has("authorDetails") && item.get("authorDetails").isJsonObject()
                ? item.getAsJsonObject("authorDetails") : null;
            if (snippet == null) continue;
            String text = snippet.has("displayMessage") ? snippet.get("displayMessage").getAsString() : null;
            // Super stickers don't carry text but still carry money — don't drop them
            if ((text == null || text.isBlank())
                && !snippet.has("superChatDetails") && !snippet.has("superStickerDetails")) continue;

            String authorId = author != null && author.has("channelId")
                ? author.get("channelId").getAsString()
                : (author != null && author.has("displayName") ? author.get("displayName").getAsString() : "yt");

            double tipUsd = extractTipUsd(snippet);
            String displayText = (text == null || text.isBlank())
                ? "[super sticker]" : text;
            sink.accept(new ChatMessage(authorId, displayText, System.currentTimeMillis(), "youtube", tipUsd));
        }
    }

    /**
     * YouTube's superChatDetails / superStickerDetails carry an
     * {@code amountMicros} field plus a {@code currency} ISO code. We
     * convert everything to USD-ish using a fixed table for the most
     * common currencies; anything we don't recognize is treated as USD
     * (rough approximation, fine for an "earn dance" trigger). Returns
     * 0.0 when the message has no tip attached.
     */
    private static double extractTipUsd(JsonObject snippet) {
        JsonObject details = null;
        if (snippet.has("superChatDetails") && snippet.get("superChatDetails").isJsonObject()) {
            details = snippet.getAsJsonObject("superChatDetails");
        } else if (snippet.has("superStickerDetails") && snippet.get("superStickerDetails").isJsonObject()) {
            details = snippet.getAsJsonObject("superStickerDetails");
        }
        if (details == null) return 0.0;

        long micros = details.has("amountMicros") && details.get("amountMicros").isJsonPrimitive()
            ? details.get("amountMicros").getAsLong() : 0L;
        if (micros <= 0L) return 0.0;
        String ccy = details.has("currency") && details.get("currency").isJsonPrimitive()
            ? details.get("currency").getAsString() : "USD";

        double native_ = micros / 1_000_000.0;
        return convertToUsd(native_, ccy);
    }

    /** Rough fixed-table conversion. Good enough for a dance trigger. */
    private static double convertToUsd(double amount, String ccy) {
        if (ccy == null || ccy.isBlank()) return amount;
        return switch (ccy.toUpperCase()) {
            case "USD" -> amount;
            case "EUR" -> amount * 1.07;
            case "GBP" -> amount * 1.25;
            case "CAD" -> amount * 0.73;
            case "AUD" -> amount * 0.65;
            case "JPY" -> amount * 0.0065;
            case "BRL" -> amount * 0.18;
            case "MXN" -> amount * 0.05;
            case "INR" -> amount * 0.012;
            case "PHP" -> amount * 0.017;
            case "KRW" -> amount * 0.00073;
            default     -> amount; // best effort
        };
    }
}
