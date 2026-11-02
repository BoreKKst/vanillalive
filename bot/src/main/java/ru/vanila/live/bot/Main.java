package ru.vanila.live.bot;

import java.nio.file.Path;
import java.util.EnumSet;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) {
        Config cfg;
        try {
            cfg = Config.load(Path.of(".env"));
        } catch (Config.ConfigException e) {
            System.err.println("Неверная конфигурация (.env):\n" + e.getMessage());
            System.exit(1);
            return;
        }

        // Бот никогда не создаёт упоминаний (@everyone, роли, пользователи).
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));

        Inbox inbox = new Inbox(500);
        DiscordListener listener = new DiscordListener(cfg, inbox);
        JDA jda;
        try {
            jda = JDABuilder.createLight(cfg.discordToken, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(listener)
                    .build()
                    .awaitReady();
        } catch (Exception e) {
            if (e.getClass().getSimpleName().equals("InvalidTokenException")) {
                LOG.error("Ошибка авторизации Discord: неверный токен (DISCORD_TOKEN)");
            } else {
                LOG.error("Не удалось подключиться к Discord: {}", e.toString());
            }
            System.exit(1);
            return;
        }

        if (jda.getGuildById(cfg.guildId) == null) {
            LOG.error("Бот не состоит на сервере (guild) {}. Добавьте бота на сервер и перезапустите.", cfg.guildId);
            jda.shutdownNow();
            System.exit(1);
            return;
        }
        if (jda.getTextChannelById(cfg.chatChannelId) == null)
            LOG.error("Канал чата {} не найден или у бота нет доступа к нему", cfg.chatChannelId);
        if (jda.getTextChannelById(cfg.consoleChannelId) == null)
            LOG.error("Канал консоли {} не найден или у бота нет доступа к нему", cfg.consoleChannelId);

        ChannelSender chatSender = new ChannelSender("chat", jda, cfg.chatChannelId, 1000);
        ChannelSender consoleSender = new ChannelSender("console", jda, cfg.consoleChannelId, 300);
        ConsoleRelay relay = new ConsoleRelay(consoleSender, new Redactor(cfg.secrets()));

        BridgeServer server;
        try {
            server = new BridgeServer(cfg, inbox, chatSender, listener::isConnected);
            server.start();
        } catch (Exception e) {
            LOG.error("Не удалось запустить HTTP API на {}:{}: {}", cfg.bridgeBind, cfg.bridgePort, e.toString());
            System.exit(1);
            return;
        }

        PterodactylConsole console = null;
        if (cfg.consoleEnabled()) {
            console = new PterodactylConsole(cfg, relay);
            console.start();
        } else {
            LOG.warn("PTERO_API_KEY не задан: консоль Minecraft в Discord отключена");
        }

        final PterodactylConsole consoleRef = console;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Остановка бота...");
            if (consoleRef != null) consoleRef.stop();
            server.stop();
            relay.close();
            chatSender.close();
            consoleSender.close();
            jda.shutdown();
        }, "vanilalive-shutdown"));

        LOG.info("Vanila Live Bot запущен");
    }
}
