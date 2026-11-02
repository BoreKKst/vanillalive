package ru.vanila.live.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Ограниченная очередь сообщений Discord → Minecraft с long-poll. Только JDK. */
final class Inbox {
    record Msg(long seq, String author, String text) {}
    record Result(long cursor, List<Msg> messages) {}

    private final ArrayDeque<Msg> queue = new ArrayDeque<>();
    private final int capacity;
    private long seq = 0;

    Inbox(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    synchronized long add(String author, String text) {
        seq++;
        queue.addLast(new Msg(seq, author, text));
        while (queue.size() > capacity) queue.removeFirst();
        notifyAll();
        return seq;
    }

    /**
     * after &lt; 0 (первый запрос) или after &gt; текущего seq (бот перезапущен):
     * вернуть текущий курсор без сообщений, чтобы не проигрывать старое.
     */
    synchronized Result poll(long after, long waitMs) throws InterruptedException {
        if (after < 0 || after > seq) return new Result(seq, List.of());
        long deadline = System.nanoTime() + waitMs * 1_000_000L;
        while (true) {
            List<Msg> out = new ArrayList<>();
            for (Msg m : queue) {
                if (m.seq() > after) {
                    out.add(m);
                    if (out.size() >= 50) break;
                }
            }
            if (!out.isEmpty()) return new Result(out.get(out.size() - 1).seq(), out);
            long remain = deadline - System.nanoTime();
            if (remain <= 0) return new Result(after, List.of());
            wait(Math.max(1L, remain / 1_000_000L));
        }
    }
}
