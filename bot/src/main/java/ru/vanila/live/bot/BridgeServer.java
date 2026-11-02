package ru.vanila.live.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Локальный HTTP API бота.
 *   POST /v1/event  - события Minecraft -> Discord (chat/join/quit)
 *   GET  /v1/poll   - long-poll: сообщения Discord -> Minecraft
 *   GET  /v1/health - проверка связи
 * Каждый запрос подписан: X-Timestamp, X-Signature = HMAC-SHA256(secret, ts.METHOD.path?query.body).
 */
final class BridgeServer {
    private static final Logger LOG = LoggerFactory.getLogger(BridgeServer.class);
    private static final int MAX_BODY = 16 * 1024;
    private static final long MAX_SKEW_MS = 60_000;

    private interface Handler { void handle(HttpExchange ex, String body) throws Exception; }

    private final Config cfg;
    private final Inbox inbox;
    private final ChannelSender chat;
    private final BooleanSupplier discordUp;
    private final HttpServer server;
    private final ExecutorService pool;
    private final AtomicLong lastAuthLog = new AtomicLong();
    private final AtomicLong lastPollAt = new AtomicLong();
    private final Map<String, Long> lastPresence = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Boolean> seen =
            Collections.synchronizedMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 2000; }
            });

    BridgeServer(Config cfg, Inbox inbox, ChannelSender chat, BooleanSupplier discordUp) throws IOException {
        this.cfg = cfg;
        this.inbox = inbox;
        this.chat = chat;
        this.discordUp = discordUp;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(cfg.bridgeBind), cfg.bridgePort), 16);
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "vanilalive-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/v1/event", ex -> guard(ex, "POST", this::onEvent));
        server.createContext("/v1/poll", ex -> guard(ex, "GET", this::onPoll));
        server.createContext("/v1/health", ex -> guard(ex, "GET", this::onHealth));
        server.createContext("/", ex -> {
            try (ex) { reply(ex, 404, error("not found")); } catch (IOException ignored) { }
        });
    }

    void start() {
        server.start();
        LOG.info("HTTP API моста слушает {}:{}", cfg.bridgeBind, cfg.bridgePort);
    }

    void stop() {
        server.stop(1);
        pool.shutdownNow();
    }

    private void guard(HttpExchange ex, String method, Handler h) {
        try (ex) {
            if (!ex.getRequestMethod().equals(method)) {
                ex.getResponseHeaders().set("Allow", method);
                reply(ex, 405, error("method not allowed"));
                return;
            }
            String cl = ex.getRequestHeaders().getFirst("Content-Length");
            if (cl != null) {
                try {
                    if (Long.parseLong(cl.trim()) > MAX_BODY) { reply(ex, 413, error("body too large")); return; }
                } catch (NumberFormatException e) { reply(ex, 400, error("bad content-length")); return; }
            }
            byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (raw.length > MAX_BODY) { reply(ex, 413, error("body too large")); return; }
            String body = new String(raw, StandardCharsets.UTF_8);
            if (!authorized(ex, body)) {
                long now = System.currentTimeMillis();
                if (now - lastAuthLog.get() > 30_000) {
                    lastAuthLog.set(now);
                    LOG.warn("Ошибка авторизации: отклонён запрос {} {} от {}", ex.getRequestMethod(),
                            ex.getRequestURI().getRawPath(), ex.getRemoteAddress().getAddress().getHostAddress());
                }
                reply(ex, 401, error("unauthorized"));
                return;
            }
            h.handle(ex, body);
        } catch (Exception e) {
            LOG.warn("Ошибка обработки HTTP-запроса: {}", e.toString());
            try { reply(ex, 500, error("internal error")); } catch (Exception ignored) { }
        }
    }

    private boolean authorized(HttpExchange ex, String body) {
        String ts = ex.getRequestHeaders().getFirst("X-Timestamp");
        String sig = ex.getRequestHeaders().getFirst("X-Signature");
        if (ts == null || sig == null) return false;
        long t;
        try { t = Long.parseLong(ts.trim()); } catch (NumberFormatException e) { return false; }
        if (Math.abs(System.currentTimeMillis() - t) > MAX_SKEW_MS) return false;
        String q = ex.getRequestURI().getRawQuery();
        String path = ex.getRequestURI().getRawPath() + (q == null ? "" : "?" + q);
        String expected = Signer.hmacHex(cfg.bridgeSecret, ts.trim() + "." + ex.getRequestMethod() + "." + path + "." + body);
        return Signer.safeEquals(expected, sig.trim().toLowerCase());
    }

    private void onHealth(HttpExchange ex, String body) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("discord", discordUp.getAsBoolean());
        reply(ex, 200, o.toString());
    }

    private void onEvent(HttpExchange ex, String body) throws IOException {
        JsonObject o;
        try {
            o = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            reply(ex, 400, error("invalid json"));
            return;
        }
        String id = str(o, "id");
        String type = str(o, "type");
        String player = Text.clean(str(o, "player"), 32);
        String msg = Text.clean(str(o, "message"), 1000);
        if (id.isBlank() || id.length() > 64 || player.isBlank()) { reply(ex, 400, error("invalid fields")); return; }
        if (!type.equals("chat") && !type.equals("join") && !type.equals("quit")) { reply(ex, 400, error("invalid type")); return; }
        if (type.equals("chat") && msg.isBlank()) { reply(ex, 400, error("empty message")); return; }
        if (!type.equals("chat")) {
            long now = System.currentTimeMillis();
            Long prev = lastPresence.put(type + "|" + player, now);
            if (lastPresence.size() > 500) lastPresence.clear();
            if (prev != null && now - prev < 5000) {
                JsonObject d = new JsonObject();
                d.addProperty("ok", true);
                d.addProperty("duplicate", true);
                reply(ex, 200, d.toString());
                return;
            }
        }
        if (seen.put(id, Boolean.TRUE) != null) {
            JsonObject d = new JsonObject();
            d.addProperty("ok", true);
            d.addProperty("duplicate", true);
            reply(ex, 200, d.toString());
            return;
        }
        String name = esc(player);
        String text = switch (type) {
            case "chat" -> "**" + name + "**: " + esc(msg);
            case "join" -> "\uD83D\uDFE2 **" + name + "** вошёл на сервер";
            default -> "\uD83D\uDD34 **" + name + "** вышел с сервера";
        };
        chat.offer(text);
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        reply(ex, 200, r.toString());
    }

    private void onPoll(HttpExchange ex, String body) throws Exception {
        long after = -1;
        int wait = 0;
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) {
            for (String p : q.split("&")) {
                int i = p.indexOf('=');
                if (i < 0) continue;
                String k = p.substring(0, i);
                String v = p.substring(i + 1);
                try {
                    if (k.equals("after")) after = Long.parseLong(v);
                    else if (k.equals("wait")) wait = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    reply(ex, 400, error("bad query"));
                    return;
                }
            }
        }
        wait = Math.max(0, Math.min(wait, 25));
        long nowMs = System.currentTimeMillis();
        long prevPoll = lastPollAt.getAndSet(nowMs);
        if (prevPoll == 0 || nowMs - prevPoll > 60_000) LOG.info("Плагин Minecraft подключился к боту");
        Inbox.Result r = inbox.poll(after, wait * 1000L);
        JsonObject o = new JsonObject();
        o.addProperty("cursor", r.cursor());
        JsonArray arr = new JsonArray();
        for (Inbox.Msg m : r.messages()) {
            JsonObject mo = new JsonObject();
            mo.addProperty("author", m.author());
            mo.addProperty("text", m.text());
            arr.add(mo);
        }
        o.add("messages", arr);
        reply(ex, 200, o.toString());
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static String esc(String s) {
        return MarkdownSanitizer.escape(s).replace("@", "@\u200b");
    }

    private static String error(String m) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", m);
        return o.toString();
    }

    private static void reply(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
