package ru.vanila.live.bot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Конфигурация бота из .env (переменные окружения имеют приоритет). Только JDK. */
final class Config {
    static final class ConfigException extends Exception {
        ConfigException(String m) { super(m); }
    }

    final String discordToken;
    final long guildId;
    final long chatChannelId;
    final long consoleChannelId;
    final String bridgeSecret;
    final int bridgePort;
    final String bridgeBind;
    final String panelUrl;
    final String serverId;
    final String panelApiKey; // null => консоль отключена

    private Config(String token, long guild, long chat, long console, String secret, int port, String bind,
                   String panelUrl, String serverId, String apiKey) {
        this.discordToken = token;
        this.guildId = guild;
        this.chatChannelId = chat;
        this.consoleChannelId = console;
        this.bridgeSecret = secret;
        this.bridgePort = port;
        this.bridgeBind = bind;
        this.panelUrl = panelUrl;
        this.serverId = serverId;
        this.panelApiKey = apiKey;
    }

    boolean consoleEnabled() { return panelApiKey != null; }

    List<String> secrets() {
        List<String> l = new ArrayList<>();
        l.add(discordToken);
        l.add(bridgeSecret);
        if (panelApiKey != null) l.add(panelApiKey);
        return l;
    }

    static Config load(Path envFile) throws ConfigException {
        Map<String, String> file = new HashMap<>();
        if (Files.isRegularFile(envFile)) {
            try {
                List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (i == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                            || (v.startsWith("'") && v.endsWith("'")))) {
                        v = v.substring(1, v.length() - 1);
                    }
                    file.put(k, v);
                }
            } catch (IOException e) {
                throw new ConfigException("Не удалось прочитать " + envFile + ": " + e.getMessage());
            }
        }
        List<String> problems = new ArrayList<>();

        String token = get(file, "DISCORD_TOKEN", "");
        if (token.isBlank() || token.equals("PUT_YOUR_TOKEN_HERE")) problems.add("DISCORD_TOKEN не задан");

        long guild = id(file, "GUILD_ID", "1565370345748299957", problems);
        long chat = id(file, "CHAT_CHANNEL_ID", "1566008093349773422", problems);
        long console = id(file, "CONSOLE_CHANNEL_ID", "1566016032504680558", problems);
        if (chat == console && chat != 0) problems.add("CHAT_CHANNEL_ID и CONSOLE_CHANNEL_ID должны отличаться");

        String secret = get(file, "BRIDGE_SECRET", "");
        if (secret.length() < 32 || secret.startsWith("CHANGE_ME"))
            problems.add("BRIDGE_SECRET должен быть случайным и не короче 32 символов");

        int port = 0;
        try {
            port = Integer.parseInt(get(file, "BRIDGE_PORT", "8765"));
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            problems.add("BRIDGE_PORT должен быть числом 1-65535");
        }
        String bind = get(file, "BRIDGE_BIND", "127.0.0.1");

        String panel = get(file, "PANEL_URL", "https://mgr.nexuz.ru");
        while (panel.endsWith("/")) panel = panel.substring(0, panel.length() - 1);
        if (!panel.startsWith("https://") && !panel.startsWith("http://")) problems.add("PANEL_URL должен начинаться с https://");
        String serverId = get(file, "SERVER_ID", "8371094b");
        if (!serverId.matches("[A-Za-z0-9-]{4,64}")) problems.add("SERVER_ID имеет неверный формат");
        String apiKey = get(file, "PTERO_API_KEY", "");
        if (apiKey.isBlank()) apiKey = null;

        if (!problems.isEmpty()) {
            throw new ConfigException(" - " + String.join("\n - ", problems));
        }
        return new Config(token, guild, chat, console, secret, port, bind, panel, serverId, apiKey);
    }

    private static String get(Map<String, String> file, String key, String def) {
        String e = System.getenv(key);
        if (e != null && !e.isBlank()) return e.trim();
        String f = file.get(key);
        return (f == null || f.isBlank()) ? def : f;
    }

    private static long id(Map<String, String> file, String key, String def, List<String> problems) {
        String v = get(file, key, def);
        try {
            long l = Long.parseLong(v);
            if (l <= 0) throw new NumberFormatException();
            return l;
        } catch (NumberFormatException e) {
            problems.add(key + " должен быть числовым Discord ID");
            return 0;
        }
    }
}
