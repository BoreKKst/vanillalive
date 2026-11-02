package ru.vanila.live.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Связь с ботом. Все сетевые операции идут в собственных потоках, главный поток не блокируется.
 *  - sender-поток: очередь событий Minecraft -> бот (ограничена, повторы с задержкой)
 *  - poller-поток: long-poll Discord -> Minecraft
 */
final class BridgeClient {
    private final JavaPlugin plugin;
    private final Logger log;
    private final String base;
    private final String secret;
    private final HttpClient http;
    private final Duration timeout;
    private final int pollWait;
    private final int maxRetries;
    private final int toMcMax;
    private final LinkedBlockingDeque<String> outbox;
    private final ConcurrentHashMap<String, Long> lastLog = new ConcurrentHashMap<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final ConcurrentHashMap<String, Long> lastPresence = new ConcurrentHashMap<>();
    private final Thread sender;
    private final Thread poller;
    private volatile boolean running = true;

    BridgeClient(JavaPlugin plugin, String baseUrl, String secret, int timeoutSec, int pollWait,
                 int maxRetries, int queueSize, int toMcMax) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.base = baseUrl;
        this.secret = secret;
        this.timeout = Duration.ofSeconds(timeoutSec);
        this.pollWait = pollWait;
        this.maxRetries = maxRetries;
        this.toMcMax = toMcMax;
        this.outbox = new LinkedBlockingDeque<>(queueSize);
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        this.sender = new Thread(this::sendLoop, "VanilaLive-Sender");
        this.poller = new Thread(this::pollLoop, "VanilaLive-Poller");
        sender.setDaemon(true);
        poller.setDaemon(true);
    }

    void start() {
        sender.start();
        poller.start();
    }

    void stop() {
        running = false;
        sender.interrupt();
        poller.interrupt();
        try {
            sender.join(2000);
            poller.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        http.shutdownNow();
    }

    /** Неблокирующая постановка события в очередь (вызывается из игровых событий). */
    void enqueue(String type, String player, String message, int maxLen) {
        String p = Text.clean(player, 32);
        String m = Text.clean(message, maxLen);
        if (p.isBlank()) return;
        if (type.equals("chat") && m.isBlank()) return;
        if (!type.equals("chat")) {
            long now = System.currentTimeMillis();
            Long prev = lastPresence.put(type + "|" + p, now);
            if (prev != null && now - prev < 5000) return; // дубль входа/выхода
            if (lastPresence.size() > 500) lastPresence.clear();
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", UUID.randomUUID().toString());
        o.addProperty("type", type);
        o.addProperty("player", p);
        o.addProperty("message", m);
        String json = o.toString();
        while (!outbox.offerLast(json)) {
            if (outbox.pollFirst() != null) logThrottled("overflow", Level.WARNING, "Очередь отправки в Discord переполнена, старые события отброшены");
        }
    }

    // ---------------- отправка Minecraft -> бот ----------------

    private void sendLoop() {
        while (running) {
            String body;
            try {
                body = outbox.take();
            } catch (InterruptedException e) {
                return;
            }
            if (!deliver(body)) return;
        }
    }

    /** @return false если поток нужно завершить. */
    private boolean deliver(String body) {
        int attempts = consecutiveFailures.get() >= 3 ? 1 : maxRetries;
        for (int attempt = 0; attempt < attempts && running; attempt++) {
            try {
                HttpResponse<String> r = http.send(request("POST", "/v1/event", body, timeout), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = r.statusCode();
                if (code >= 200 && code < 300) {
                    if (consecutiveFailures.getAndSet(0) >= 3) log.info("Связь с ботом восстановлена");
                    return true;
                }
                if (code == 401 || code == 403) {
                    logThrottled("auth", Level.SEVERE, "Ошибка авторизации: бот отклонил запрос (проверьте bridge.secret и системное время)");
                    consecutiveFailures.incrementAndGet();
                    return true;
                }
                if (code >= 400 && code < 500) {
                    logThrottled("send4xx", Level.WARNING, "Ошибка отправки сообщения: бот ответил HTTP " + code);
                    return true;
                }
                logThrottled("send5xx", Level.WARNING, "Ошибка отправки сообщения: бот ответил HTTP " + code);
            } catch (InterruptedException e) {
                return false;
            } catch (IOException e) {
                logThrottled("conn", Level.WARNING, "Не удалось связаться с ботом (отправка): " + e.getClass().getSimpleName());
            } catch (RuntimeException e) {
                logThrottled("senderr", Level.WARNING, "Ошибка отправки сообщения: " + e);
                return true;
            }
            consecutiveFailures.incrementAndGet();
            if (!sleep(Math.min(1000L << attempt, 15_000L))) return false;
        }
        return running;
    }

    // ---------------- приём Discord -> Minecraft ----------------

    private void pollLoop() {
        long cursor = -1;
        long backoff = 2_000;
        boolean up = false;
        while (running) {
            try {
                String path = "/v1/poll?after=" + cursor + "&wait=" + pollWait;
                HttpResponse<String> r = http.send(request("GET", path, "", Duration.ofSeconds(pollWait + 15L)),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = r.statusCode();
                if (code == 200) {
                    if (!up) {
                        up = true;
                        log.info("Соединение с ботом установлено");
                    }
                    backoff = 2_000;
                    JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                    cursor = o.get("cursor").getAsLong();
                    JsonArray arr = o.getAsJsonArray("messages");
                    for (JsonElement e : arr) {
                        JsonObject m = e.getAsJsonObject();
                        broadcast(m.get("author").getAsString(), m.get("text").getAsString());
                    }
                } else {
                    up = false;
                    if (code == 401 || code == 403)
                        logThrottled("auth", Level.SEVERE, "Ошибка авторизации: бот отклонил запрос (проверьте bridge.secret и системное время)");
                    else
                        logThrottled("poll", Level.WARNING, "Не удалось связаться с ботом (получение): HTTP " + code);
                    if (!sleep(backoff)) return;
                    backoff = Math.min(backoff * 2, 60_000);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (!running) return;
                up = false;
                logThrottled("poll", Level.WARNING, "Не удалось связаться с ботом (получение): " + e.getClass().getSimpleName()
                        + " — повтор через " + (backoff / 1000) + " с (/list продолжает работать)");
                if (!sleep(backoff)) return;
                backoff = Math.min(backoff * 2, 60_000);
            }
        }
    }

    private void broadcast(String author, String text) {
        String a = Text.clean(author, 32);
        String t = Text.clean(text, toMcMax);
        if (t.isBlank()) return;
        if (a.isBlank()) a = "Discord";
        Component c = Component.text("[Discord] ", NamedTextColor.BLUE)
                .append(Component.text(a + ": ", NamedTextColor.GRAY))
                .append(Component.text(t, NamedTextColor.WHITE));
        if (!running) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getServer().sendMessage(c));
        } catch (RuntimeException e) {
            // плагин выключается
        }
    }

    // ---------------- утилиты ----------------

    private HttpRequest request(String method, String pathAndQuery, String body, Duration to) {
        URI uri = URI.create(base + pathAndQuery);
        String ts = Long.toString(System.currentTimeMillis());
        String signedPath = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String sig = Signer.hmacHex(secret, ts + "." + method + "." + signedPath + "." + body);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(to)
                .header("X-Timestamp", ts)
                .header("X-Signature", sig)
                .header("Content-Type", "application/json; charset=utf-8");
        return method.equals("POST") ? b.POST(HttpRequest.BodyPublishers.ofString(body)).build() : b.GET().build();
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return running;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void logThrottled(String key, Level level, String msg) {
        long now = System.currentTimeMillis();
        Long prev = lastLog.get(key);
        if (prev == null || now - prev > 60_000) {
            lastLog.put(key, now);
            log.log(level, msg);
        }
    }
}
