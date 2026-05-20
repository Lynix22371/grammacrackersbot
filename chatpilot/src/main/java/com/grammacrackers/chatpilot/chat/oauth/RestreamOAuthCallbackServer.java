package com.grammacrackers.chatpilot.chat.oauth;

import com.grammacrackers.chatpilot.ChatPilotMod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny single-thread HTTP server bound to 127.0.0.1:8765 that catches the
 * Restream OAuth callback. When Restream redirects the browser to
 * http://127.0.0.1:8765/callback?code=...&state=..., this server reads the
 * code, asks {@link RestreamAuthManager} to exchange it for tokens, then
 * shuts itself down so the port is freed.
 *
 * The server only runs during the brief authorization window. It does not
 * accept any other path and rejects requests from non-loopback addresses.
 */
public class RestreamOAuthCallbackServer {

    private final int port;
    private final RestreamAuthManager auth;
    private HttpServer server;

    public RestreamOAuthCallbackServer(int port, RestreamAuthManager auth) {
        this.port = port;
        this.auth = auth;
    }

    public synchronized void start() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/callback", this::handleCallback);
            server.createContext("/", ex -> {
                try { respond(ex, 404, "Not found"); }
                catch (Exception e) { ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] respond failed", e); }
            });
            server.setExecutor(null);
            server.start();
            ChatPilotMod.LOGGER.info("[ChatPilot][OAuth] callback server listening on 127.0.0.1:{}", port);
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.error("[ChatPilot][OAuth] could not start callback server on port {}", port, t);
            server = null;
        }
    }

    public synchronized void stop() {
        if (server == null) return;
        try { server.stop(0); } catch (Throwable ignored) {}
        server = null;
        ChatPilotMod.LOGGER.info("[ChatPilot][OAuth] callback server stopped");
    }

    private void handleCallback(HttpExchange ex) {
        try {
            // Loopback only
            String remote = ex.getRemoteAddress().getAddress().getHostAddress();
            if (!remote.equals("127.0.0.1") && !remote.equals("0:0:0:0:0:0:0:1")) {
                respond(ex, 403, "forbidden");
                return;
            }
            URI uri = ex.getRequestURI();
            Map<String, String> q = parseQuery(uri.getRawQuery());
            String code  = q.get("code");
            String state = q.get("state");
            if (code == null) {
                respond(ex, 400, htmlPage("ChatPilot",
                    "Authorization was cancelled or returned no code.",
                    "You can close this tab and try /restream login again in Minecraft."));
                return;
            }
            if (!auth.stateMatches(state)) {
                ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] state mismatch on callback");
                respond(ex, 400, htmlPage("ChatPilot",
                    "OAuth state mismatch. Possible CSRF.",
                    "Close this tab and try again from Minecraft."));
                return;
            }
            String redirectUri = auth.getCredentials().redirectUri;
            boolean ok = auth.exchangeCode(code, redirectUri);
            if (ok) {
                respond(ex, 200, htmlPage("ChatPilot",
                    "Authorized successfully!",
                    "You can close this tab. Minecraft will start reading chat shortly."));
            } else {
                respond(ex, 500, htmlPage("ChatPilot",
                    "Token exchange failed.",
                    "Check Minecraft's latest.log for details."));
            }
            // Done with the OAuth flow, free the port
            new Thread(this::stop, "ChatPilot-OAuthShutdown").start();
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][OAuth] callback threw", t);
            try { respond(ex, 500, "internal error"); } catch (Throwable ignored) {}
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static void respond(HttpExchange ex, int code, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (var os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String htmlPage(String title, String h1, String body) {
        return "<!doctype html><html><head><meta charset=utf-8><title>" + escape(title) + "</title>"
             + "<style>body{font:16px system-ui;background:#0f1116;color:#eee;display:flex;align-items:center;"
             + "justify-content:center;height:100vh;margin:0}div{max-width:480px;padding:32px;background:#1b2030;"
             + "border-radius:12px;box-shadow:0 8px 32px rgba(0,0,0,.4)}h1{margin-top:0;color:#ffe066}p{color:#ccc;line-height:1.5}</style>"
             + "</head><body><div><h1>" + escape(h1) + "</h1><p>" + escape(body) + "</p></div></body></html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
