package ru.vanila.live.plugin;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Игровые события -> очередь отправки. Сетевых вызовов здесь нет. */
final class BridgeListener implements Listener {
    private final VanilaLivePlugin plugin;

    BridgeListener(VanilaLivePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        BridgeClient c = plugin.client();
        if (c == null) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        c.enqueue("chat", e.getPlayer().getName(), text, plugin.toDiscordMax());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        BridgeClient c = plugin.client();
        if (c == null || !plugin.sendJoinQuit()) return;
        c.enqueue("join", e.getPlayer().getName(), "", 0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        BridgeClient c = plugin.client();
        if (c == null || !plugin.sendJoinQuit()) return;
        c.enqueue("quit", e.getPlayer().getName(), "", 0);
    }
}
