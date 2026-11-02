package ru.vanila.live.plugin;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class VanilaLivePlugin extends JavaPlugin {
    private volatile BridgeClient client;
    private boolean sendJoinQuit;
    private int toDiscordMax;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration c = getConfig();

        int pageSize = Math.max(1, Math.min(100, c.getInt("list.page-size", 10)));
        PluginCommand list = getCommand("list");
        if (list != null) list.setExecutor(new ListCommand(pageSize));

        sendJoinQuit = c.getBoolean("chat.send-join-quit", true);
        toDiscordMax = Math.max(16, Math.min(1500, c.getInt("chat.minecraft-to-discord-max-length", 500)));
        int toMcMax = Math.max(16, Math.min(256, c.getInt("chat.discord-to-minecraft-max-length", 256)));

        String url = c.getString("bridge.url", "").trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        String secret = c.getString("bridge.secret", "");
        List<String> problems = new ArrayList<>();
        if (!(url.startsWith("https://") || url.startsWith("http://")) || url.contains("CHANGE-ME"))
            problems.add("bridge.url не настроен (нужен адрес вида https://host без пути)");
        else if (url.indexOf('/', url.indexOf("://") + 3) >= 0)
            problems.add("bridge.url должен быть без пути (только схема и хост)");
        if (secret.length() < 32 || secret.startsWith("CHANGE_ME"))
            problems.add("bridge.secret должен быть случайным и не короче 32 символов");

        if (problems.isEmpty()) {
            if (url.startsWith("http://"))
                getLogger().warning("bridge.url использует HTTP без шифрования. Рекомендуется HTTPS.");
            client = new BridgeClient(this, url, secret,
                    Math.max(2, c.getInt("bridge.request-timeout-seconds", 10)),
                    Math.max(1, Math.min(15, c.getInt("bridge.poll-wait-seconds", 8))),
                    Math.max(1, Math.min(10, c.getInt("bridge.max-retries", 5))),
                    Math.max(10, Math.min(5000, c.getInt("bridge.queue-size", 500))),
                    toMcMax);
            getServer().getPluginManager().registerEvents(new BridgeListener(this), this);
            client.start();
            getLogger().info("Мост с Discord-ботом запущен");
        } else {
            for (String p : problems) getLogger().severe("Неверная конфигурация: " + p);
            getLogger().warning("Мост с Discord отключён. Команда /list работает независимо.");
        }
        getLogger().info("Плагин VanilaLive включён");
    }

    @Override
    public void onDisable() {
        BridgeClient c = client;
        client = null;
        if (c != null) c.stop();
        getLogger().info("Плагин VanilaLive выключен");
    }

    BridgeClient client() { return client; }
    boolean sendJoinQuit() { return sendJoinQuit; }
    int toDiscordMax() { return toDiscordMax; }
}
