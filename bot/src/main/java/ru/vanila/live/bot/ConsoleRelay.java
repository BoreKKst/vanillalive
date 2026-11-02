package ru.vanila.live.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Собирает строки консоли в пачки, вырезает секреты, схлопывает повторы и отправляет в канал консоли. */
final class ConsoleRelay implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ConsoleRelay.class);
    private static final int MAX_LINE = 500;
    private static final int MAX_CHUNK = 1800;

    private final LinkedBlockingDeque<String> queue = new LinkedBlockingDeque<>(2000);
    private final ChannelSender sender;
    private final Redactor redactor;
    private final ScheduledExecutorService scheduler;

    ConsoleRelay(ChannelSender sender, Redactor redactor) {
        this.sender = sender;
        this.redactor = redactor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vanilalive-console-flush");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flush, 2, 2, TimeUnit.SECONDS);
    }

    void addLine(String raw) {
        if (raw == null) return;
        for (String l : raw.split("\\r?\\n")) {
            if (l.isBlank()) continue;
            while (!queue.offerLast(l)) queue.pollFirst();
        }
    }

    private void flush() {
        try {
            List<String> batch = new ArrayList<>();
            queue.drainTo(batch, 500);
            if (batch.isEmpty()) return;

            List<String> lines = new ArrayList<>();
            String prev = null;
            int repeat = 1;
            for (String raw : batch) {
                String line = redactor.redact(raw).strip();
                if (line.isEmpty()) continue;
                if (line.length() > MAX_LINE) line = line.substring(0, MAX_LINE) + "…";
                line = line.replace("```", "'''");
                if (line.equals(prev)) {
                    repeat++;
                    continue;
                }
                if (prev != null) lines.add(repeat > 1 ? prev + " (x" + repeat + ")" : prev);
                prev = line;
                repeat = 1;
            }
            if (prev != null) lines.add(repeat > 1 ? prev + " (x" + repeat + ")" : prev);

            StringBuilder cur = new StringBuilder();
            for (String l : lines) {
                if (cur.length() > 0 && cur.length() + l.length() + 1 > MAX_CHUNK) {
                    sender.offer("```\n" + cur + "\n```");
                    cur.setLength(0);
                }
                if (cur.length() > 0) cur.append('\n');
                cur.append(l);
            }
            if (cur.length() > 0) sender.offer("```\n" + cur + "\n```");
        } catch (Throwable t) {
            LOG.warn("Ошибка обработки строк консоли: {}", t.toString());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
