package ru.vanila.live.bot;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Отправка в один Discord-канал: ограниченная очередь, отдельный поток, повторы с задержкой. */
final class ChannelSender implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelSender.class);

    private final String name;
    private final JDA jda;
    private final long channelId;
    private final LinkedBlockingDeque<String> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;

    ChannelSender(String name, JDA jda, long channelId, int capacity) {
        this.name = name;
        this.jda = jda;
        this.channelId = channelId;
        this.queue = new LinkedBlockingDeque<>(capacity);
        this.worker = new Thread(this::run, "vanilalive-sender-" + name);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** Не блокирует. При переполнении выбрасывает самое старое сообщение. */
    void offer(String text) {
        for (String part : Text.split(text, 1990)) {
            while (!queue.offerLast(part)) {
                if (queue.pollFirst() != null) dropped.incrementAndGet();
            }
        }
    }

    private void run() {
        while (running) {
            String msg;
            try {
                msg = queue.take();
            } catch (InterruptedException e) {
                break;
            }
            long d = dropped.getAndSet(0);
            if (d > 0) LOG.warn("[{}] Очередь переполнена, отброшено сообщений: {}", name, d);
            send(msg);
        }
    }

    private void send(String msg) {
        for (int attempt = 0; attempt < 4 && running; attempt++) {
            try {
                TextChannel ch = jda.getTextChannelById(channelId);
                if (ch == null) throw new IllegalStateException("текстовый канал " + channelId + " не найден");
                ch.sendMessage(msg).complete();
                return;
            } catch (Exception e) {
                if (!running) return;
                LOG.warn("Ошибка отправки сообщения [{}], попытка {}/4: {}", name, attempt + 1, e.toString());
                try {
                    Thread.sleep(1000L << attempt);
                } catch (InterruptedException ie) {
                    running = false;
                    return;
                }
            }
        }
        if (running) LOG.error("Сообщение [{}] не удалось отправить, оно отброшено", name);
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }
}
