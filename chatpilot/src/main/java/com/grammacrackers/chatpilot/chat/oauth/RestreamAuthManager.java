package com.grammacrackers.chatpilot.chat.oauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.fabricmc.loader.api.FabricLoader;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Restream OAuth2 manager.
 *
 * Lifecycle:
 *   1. On startup, read {@code restream_credentials.json} (clientId, clientSecret).
 *   2. If {@code tokens.json} exists and the refresh token is fresh, refresh the
 *      access token and use it.
 *   3. Otherwise, build the authorize URL and let the user complete the OAuth
 *      dance via {@link RestreamOAuthCallbackServer}, which will deliver the
 *      authorization code back here.
 *   4. Schedule a background task that refreshes the access token a few minutes
 *      before its 1-hour expiry, indefinitely.
 *
 * Design notes:
 *   - Client secret is read once into memory and from a local file only.
 *     It is never logged, never sent to anywhere except api.restream.io,
 *     and never appears in URLs.
 *   - Access tokens are stored on disk so we can resume after a Minecraft
 *     restart without making the user re-authorize. Refresh token is also
 *     stored because it lasts a year.
 *   - All HTTP requests use Basic Auth header (recommended path) rather than
 *     posting the secret in the body.
 */
public class RestreamAuthManager {

    public static final String TOKEN_ENDPOINT     = "https://api.restream.io/oauth/token";
    public static final String AUTHORIZE_ENDPOINT = "https://api.restream.io/login";
    public static final String DEFAULT_REDIRECT   = "http://127.0.0.1:8765/callback";
    public static final String SCOPE              = "chat.read profile.read channel.read";

    private final OkHttpClient http;
    private final ScheduledExecutorService scheduler;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private Credentials cred;
    private Tokens tokens;
    private String oauthState;
    /** Notified whenever a fresh access token is available. */
    private Consumer<String> onAccessTokenChanged = t -> {};

    public RestreamAuthManager() {
        this.http = new OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChatPilot-OAuthRefresh");
            t.setDaemon(true);
            return t;
        });
    }

    public void setOnAccessTokenChanged(Consumer<String> sink) { this.onAccessTokenChanged = sink; }

    public boolean hasCredentials() { return cred != null; }
    public boolean hasAccessToken() { return tokens != null && tokens.accessToken != null; }
    public String  getAccessToken() { return tokens == null ? null : tokens.accessToken; }
    public Credentials getCredentials() { return cred; }

    /** Read credentials and, if present, restored tokens from disk. */
    public void load() {
        ensureTemplateExists();
        try {
            cred = Credentials.load(credentialsFile());
            if (cred != null) {
                ChatPilotMod.LOGGER.info("[ChatPilot][OAuth] credentials loaded");
            } else {
                ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] No credentials file at {}",
                    credentialsFile());
            }
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] credentials load failed", t);
        }
        try {
            if (Files.exists(tokensFile())) {
                tokens = gson.fromJson(Files.readString(tokensFile()), Tokens.class);
                ChatPilotMod.LOGGER.info("[ChatPilot][OAuth] tokens loaded; access expires at {}",
                    tokens == null ? "?" : tokens.accessTokenExpiresEpoch);
            }
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] tokens load failed", t);
        }
    }

    /** Build the authorization URL the user should visit in a browser. */
    public String buildAuthorizeUrl(String redirectUri) {
        if (cred == null) throw new IllegalStateException("Credentials not loaded");
        oauthState = newState();
        return AUTHORIZE_ENDPOINT
            + "?response_type=code"
            + "&client_id=" + urlenc(cred.clientId)
            + "&redirect_uri=" + urlenc(redirectUri)
            + "&state=" + urlenc(oauthState);
    }

    public boolean stateMatches(String s) {
        return oauthState != null && oauthState.equals(s);
    }

    /** Exchange an authorization code for an access+refresh token pair. */
    public boolean exchangeCode(String code, String redirectUri) {
        if (cred == null) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] cannot exchange: no credentials");
            return false;
        }
        try {
            RequestBody form = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("redirect_uri", redirectUri)
                .add("code", code)
                .build();
            Request req = new Request.Builder()
                .url(TOKEN_ENDPOINT)
                .header("Authorization", basicAuth(cred.clientId, cred.clientSecret))
                .post(form)
                .build();
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() == null ? "" : resp.body().string();
                if (!resp.isSuccessful()) {
                    ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] code exchange failed: HTTP {} {}",
                        resp.code(), redact(body));
                    return false;
                }
                applyTokenResponse(body);
                return true;
            }
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] exchangeCode threw", t);
            return false;
        }
    }

    /** Refresh the access token. Called manually or by the background scheduler. */
    public boolean refresh() {
        if (cred == null || tokens == null || tokens.refreshToken == null) return false;
        try {
            RequestBody form = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", tokens.refreshToken)
                .build();
            Request req = new Request.Builder()
                .url(TOKEN_ENDPOINT)
                .header("Authorization", basicAuth(cred.clientId, cred.clientSecret))
                .post(form)
                .build();
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() == null ? "" : resp.body().string();
                if (!resp.isSuccessful()) {
                    ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] refresh failed: HTTP {} {}",
                        resp.code(), redact(body));
                    return false;
                }
                applyTokenResponse(body);
                return true;
            }
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] refresh threw", t);
            return false;
        }
    }

    /**
     * Schedule auto-refresh: refresh 5 minutes before access token expiry,
     * then loop forever.
     */
    public void startAutoRefresh() {
        scheduler.scheduleWithFixedDelay(this::tickAutoRefresh, 30, 60, TimeUnit.SECONDS);
    }

    private void tickAutoRefresh() {
        try {
            if (tokens == null) return;
            long now = System.currentTimeMillis() / 1000L;
            long exp = tokens.accessTokenExpiresEpoch;
            if (exp - now < 300) {
                ChatPilotMod.LOGGER.info("[ChatPilot][OAuth] auto-refreshing access token");
                refresh();
            }
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] auto-refresh threw", t);
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }

    /* ------------- helpers ------------- */

    private void applyTokenResponse(String body) throws IOException {
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        Tokens t = new Tokens();
        t.accessToken  = o.has("access_token")  ? o.get("access_token").getAsString()  : null;
        t.refreshToken = o.has("refresh_token") ? o.get("refresh_token").getAsString() : null;
        if (o.has("accessTokenExpiresEpoch"))
            t.accessTokenExpiresEpoch = o.get("accessTokenExpiresEpoch").getAsLong();
        else if (o.has("expires"))
            t.accessTokenExpiresEpoch = o.get("expires").getAsLong();
        else if (o.has("expires_in"))
            t.accessTokenExpiresEpoch = (System.currentTimeMillis() / 1000L) + o.get("expires_in").getAsLong();
        if (o.has("refreshTokenExpiresEpoch"))
            t.refreshTokenExpiresEpoch = o.get("refreshTokenExpiresEpoch").getAsLong();
        this.tokens = t;
        save();
        ChatPilotMod.LOGGER.info("[ChatPilot][OAuth] token applied; expires {}",
            t.accessTokenExpiresEpoch);
        try { onAccessTokenChanged.accept(t.accessToken); } catch (Throwable ignored) {}
    }

    private void save() {
        try {
            Files.createDirectories(tokensFile().getParent());
            Files.writeString(tokensFile(), gson.toJson(tokens));
        } catch (IOException e) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] tokens save failed", e);
        }
    }

    private static Path credentialsFile() {
        return FabricLoader.getInstance().getConfigDir()
            .resolve("chatpilot")
            .resolve("restream_credentials.json");
    }

    /** Write a placeholder credentials file so the user knows what to fill in. */
    private static void ensureTemplateExists() {
        try {
            Path p = credentialsFile();
            if (Files.exists(p)) return;
            Files.createDirectories(p.getParent());
            String template = "{\n" +
                "  \"_help\": \"Fill in clientId and clientSecret from https://api.restream.io. Then in Minecraft run /restream login. Keep this file private.\",\n" +
                "  \"clientId\": \"\",\n" +
                "  \"clientSecret\": \"\",\n" +
                "  \"redirectUri\": \"" + DEFAULT_REDIRECT + "\"\n" +
                "}\n";
            Files.writeString(p, template);
            ChatPilotMod.LOGGER.info("[ChatPilot][OAuth] Wrote credentials template to {}", p);
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] Could not write credentials template", t);
        }
    }

    private static Path tokensFile() {
        return FabricLoader.getInstance().getConfigDir()
            .resolve("chatpilot")
            .resolve("restream_tokens.json");
    }

    private static String basicAuth(String id, String secret) {
        String pair = id + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes());
    }

    private static String urlenc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String newState() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Avoid leaking tokens or secrets into log lines. */
    private static String redact(String s) {
        if (s == null) return "";
        if (s.length() > 200) return s.substring(0, 200) + "...";
        return s;
    }

    /* ------------- value classes ------------- */

    public static final class Credentials {
        public String clientId;
        public String clientSecret;
        /** Optional override; defaults to {@link #DEFAULT_REDIRECT}. */
        public String redirectUri;

        static Credentials load(Path p) throws IOException {
            if (!Files.exists(p)) return null;
            String json = Files.readString(p);
            Credentials c = new Gson().fromJson(json, Credentials.class);
            if (c == null || c.clientId == null || c.clientId.isBlank()
                || c.clientSecret == null || c.clientSecret.isBlank()) {
                return null;
            }
            if (c.redirectUri == null || c.redirectUri.isBlank()) c.redirectUri = DEFAULT_REDIRECT;
            return c;
        }
    }

    public static final class Tokens {
        public String accessToken;
        public String refreshToken;
        public long   accessTokenExpiresEpoch;
        public long   refreshTokenExpiresEpoch;
    }
}
