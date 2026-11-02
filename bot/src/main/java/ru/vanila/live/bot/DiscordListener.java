package ru.vanila.live.bot;

import java.util.concurrent.CompletableFuture;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Discord -> Minecraft и Discord slash-команды. */
final class DiscordListener extends ListenerAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(DiscordListener.class);
    private final Config cfg;
    private final Inbox inbox;
    private final MinecraftStatus minecraft;
    private volatile boolean connected;

    DiscordListener(Config cfg, Inbox inbox) {
        this.cfg=cfg; this.inbox=inbox; this.minecraft=new MinecraftStatus(cfg.minecraftHost,cfg.minecraftPort);
    }
    boolean isConnected(){return connected;}

    @Override public void onReady(ReadyEvent e){connected=true;LOG.info("Discord подключён как {}",e.getJDA().getSelfUser().getName());}
    @Override public void onSessionDisconnect(SessionDisconnectEvent e){connected=false;LOG.warn("Discord отключён, JDA переподключается");}
    @Override public void onSessionResume(SessionResumeEvent e){connected=true;LOG.info("Discord: сессия восстановлена");}
    @Override public void onSessionRecreate(SessionRecreateEvent e){connected=true;LOG.info("Discord: сессия пересоздана");}

    @Override public void onMessageReceived(MessageReceivedEvent e){
        try {
            if(!e.isFromGuild()||e.getGuild().getIdLong()!=cfg.guildId||e.getChannel().getIdLong()!=cfg.chatChannelId) return;
            if(e.getAuthor().isBot()||e.isWebhookMessage()||e.getAuthor().getIdLong()==e.getJDA().getSelfUser().getIdLong()||e.getMember()==null)return;
            Message m=e.getMessage(); String value=Text.clean(m.getContentDisplay(),256);
            if(!m.getAttachments().isEmpty()) value=value.isBlank()?"[вложение]":value+" [вложение]";
            if(value.isBlank())return;
            String author=Text.clean(e.getMember().getEffectiveName(),32); if(author.isBlank())author="Discord";
            inbox.add(author,value);
        }catch(Exception ex){LOG.warn("Ошибка обработки сообщения Discord: {}",ex.toString());}
    }

    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event){
        if(!event.getName().equals("list")||event.getGuild()==null||event.getGuild().getIdLong()!=cfg.guildId)return;
        event.deferReply().queue(hook -> CompletableFuture.runAsync(() -> {
            try {
                MinecraftStatus.Status s=minecraft.fetch();
                StringBuilder reply=new StringBuilder("**Онлайн: ").append(s.online()).append('/').append(s.max()).append("**");
                if(!s.sample().isEmpty()) {
                    reply.append("\n").append(String.join(", ",s.sample()));
                    if(s.sample().size()<s.online()) reply.append("\n_Сервер передал только часть имён игроков через статус Minecraft._");
                } else if(s.online()>0) reply.append("\n_Сервер не передал имена игроков в статусе._");
                else reply.append("\nСейчас игроков нет.");
                hook.editOriginal(reply.toString()).queue();
            }catch(Exception ex){
                LOG.warn("Не удалось получить статус Minecraft: {}",ex.toString());
                hook.editOriginal("Не удалось получить список игроков. Сервер может быть недоступен или статус-запросы отключены.").queue();
            }
        }));
    }
}
