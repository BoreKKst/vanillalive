package ru.vanila.live.bot;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Discord -> Minecraft: принимает сообщения только из канала чата нужной гильдии. */
final class DiscordListener extends ListenerAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(DiscordListener.class);

    private final Config cfg;
    private final Inbox inbox;
    private volatile boolean connected;

    DiscordListener(Config cfg, Inbox inbox) {
        this.cfg = cfg;
        this.inbox = inbox;
    }

    boolean isConnected() { return connected; }

    @Override
    public void onReady(ReadyEvent e) {
        connected = true;
        LOG.info("Discord подключён как {}", e.getJDA().getSelfUser().getName());
    }

    @Override
    public void onSessionDisconnect(SessionDisconnectEvent e) {
        connected = false;
        LOG.warn("Discord отключён, JDA попытается переподключиться");
    }

    @Override
    public void onSessionResume(SessionResumeEvent e) {
        connected = true;
        LOG.info("Discord: сессия восстановлена");
    }

    @Override
    public void onSessionRecreate(SessionRecreateEvent e) {
        connected = true;
        LOG.info("Discord: сессия пересоздана");
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent e) {
        try {
            if (!e.isFromGuild()) return;
            if (e.getGuild().getIdLong() != cfg.guildId) return;
            if (e.getChannel().getIdLong() != cfg.chatChannelId) return;
            if (e.getAuthor().isBot() || e.isWebhookMessage() || e.getAuthor().getIdLong() == e.getJDA().getSelfUser().getIdLong()) return;
            if (e.getMember() == null) return;

            Message m = e.getMessage();
            String text = Text.clean(m.getContentDisplay(), 256);
            if (!m.getAttachments().isEmpty()) text = text.isBlank() ? "[вложение]" : text + " [вложение]";
            if (text.isBlank()) return;
            String author = Text.clean(e.getMember().getEffectiveName(), 32);
            if (author.isBlank()) author = "Discord";
            inbox.add(author, text);
        } catch (Exception ex) {
            LOG.warn("Ошибка обработки сообщения Discord: {}", ex.toString());
        }
    }
}
