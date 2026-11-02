package ru.vanila.live.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент консоли через Pterodactyl Client API:
 * GET /api/client/servers/{id}/websocket (Bearer API key) -> {data:{token,socket}},
 * затем WebSocket, первое сообщение {"event":"auth","args":[token]}.
 * События: "auth success", "console output", "token expiring", "token expired".
 */
final class PterodactylConsole {
    private static final Logger LOG = LoggerFactory.getLogger(PterodactylConsole.class);

    private record Creds(String token, String socket) {}

    private static final class AuthException extends Exception {
        AuthException(String m) { super(m); }
    }

    private final String panel;
    private final String serverId;
    private final String apiKey;
    private final ConsoleRelay relay;
    private final HttpClient http;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile WebSocket ws;

    PterodactylConsole(Config cfg, ConsoleRelay relay) {
        this.panel = cfg.panelUrl;
        this.serverId = cfg.serverId;
        this.apiKey = cfg.panelApiKey;
        this.relay = relay;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.thread = new Thread(this::loop, "vanilalive-console");
        this.thread.setDaemon(true);
    }

    void start() { thread.start(); }

    void stop() {
        running = false;
        WebSocket w = ws;
        if (w != null) {
            try { w.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").orTimeout(2, TimeUnit.SECONDS); }
            catch (Exception ignored) { }
            w.abort();
        }
        thread.interrupt();
        try { thread.join(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        http.shutdownNow();
    }

    private void loop() {
        long backoff = 5_000;
        while (running) {
            try {
                Creds c = fetchCreds();
                Session s = new Session(c.token());
                ws = http.newWebSocketBuilder()
                        .header("Origin", panel)
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(URI.create(c.socket()), s)
                        .get(15, TimeUnit.SECONDS);
                String reason = s.closed.get();
                if (!running) return;
                if (s.authed) backoff = 5_000;
                LOG.warn("Консоль Nexuz отключена ({}), переподключение через {} с", reason, backoff / 1000);
            } catch (InterruptedException e) {
                return;
            } catch (AuthException e) {
                LOG.error("Ошибка авторизации Pterodactyl API: {}", e.getMessage());
                backoff = Math.max(backoff, 60_000);
            } catch (Exception e) {
                if (!running) return;
                LOG.warn("Ошибка соединения с консолью Nexuz: {}", e.toString());
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(backoff * 2, 300_000);
        }
    }

    private Creds fetchCreds() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(panel + "/api/client/servers/" + serverId + "/websocket"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "Application/vnd.pterodactyl.v1+json")
                .header("Content-Type", "application/json")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code == 401 || code == 403) throw new AuthException("HTTP " + code + " (проверьте PTERO_API_KEY и права на сервер)");
        if (code == 404) throw new AuthException("HTTP 404 (проверьте PANEL_URL и SERVER_ID)");
        if (code != 200) throw new IllegalStateException("Панель ответила HTTP " + code);
        JsonObject data = JsonParser.parseString(resp.body()).getAsJsonObject().getAsJsonObject("data");
        return new Creds(data.get("token").getAsString(), data.get("socket").getAsString());
    }

    private static String authMessage(String token) {
        JsonObject o = new JsonObject();
        o.addProperty("event", "auth");
        JsonArray a = new JsonArray();
        a.add(token);
        o.add("args", a);
        return o.toString();
    }

    private final class Session implements WebSocket.Listener {
        final CompletableFuture<String> closed = new CompletableFuture<>();
        volatile boolean authed;
        private final String token;
        private final StringBuilder buf = new StringBuilder();

        Session(String token) { this.token = token; }

        @Override
        public void onOpen(WebSocket w) {
            w.sendText(authMessage(token), true);
            w.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (buf.length() > 1_000_000) {
                buf.setLength(0);
                w.abort();
                return null;
            }
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                try {
                    handle(w, msg);
                } catch (Exception e) {
                    LOG.debug("Некорректное сообщение консоли: {}", e.toString());
                }
            }
            w.request(1);
            return null;
        }

        private void handle(WebSocket w, String msg) {
            JsonObject o = JsonParser.parseString(msg).getAsJsonObject();
            String event = o.has("event") ? o.get("event").getAsString() : "";
            JsonArray args = o.has("args") && o.get("args").isJsonArray() ? o.getAsJsonArray("args") : new JsonArray();
            switch (event) {
                case "auth success" -> {
                    authed = true;
                    LOG.info("Консоль Nexuz подключена (WebSocket авторизован)");
                }
                case "console output" -> {
                    for (JsonElement e : args) if (e.isJsonPrimitive()) relay.addLine(e.getAsString());
                }
                case "token expiring" -> CompletableFuture.runAsync(() -> {
                    try {
                        w.sendText(authMessage(fetchCreds().token()), true);
                    } catch (Exception e) {
                        LOG.warn("Не удалось обновить токен консоли: {}", e.toString());
                    }
                });
                case "token expired", "jwt error" -> w.sendClose(WebSocket.NORMAL_CLOSURE, "reauth");
                default -> { }
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
            closed.complete("закрыто: " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable error) {
            closed.complete("ошибка: " + error);
        }
    }
}
