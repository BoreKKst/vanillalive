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
    private static final Logger LOG=LoggerFactory.getLogger(Main.class);
    private Main(){}
    public static void main(String[] args){
        Config cfg;
        try{cfg=Config.load(Path.of(".env"));}
        catch(Config.ConfigException e){System.err.println("Неверная конфигурация (.env):\n"+e.getMessage());System.exit(1);return;}
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
        Inbox inbox=new Inbox(500); DiscordListener listener=new DiscordListener(cfg,inbox); JDA jda;
        try{
            jda=JDABuilder.createLight(cfg.discordToken,GatewayIntent.GUILD_MESSAGES,GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(listener).build().awaitReady();
        }catch(Exception e){
            if(e.getClass().getSimpleName().equals("InvalidTokenException"))LOG.error("Неверный Discord токен (DISCORD_TOKEN)");
            else LOG.error("Не удалось подключиться к Discord: {}",e.toString());
            System.exit(1);return;
        }
        var guild=jda.getGuildById(cfg.guildId);
        if(guild==null){LOG.error("Бот не состоит на Discord-сервере {}",cfg.guildId);jda.shutdownNow();System.exit(1);return;}
        if(jda.getTextChannelById(cfg.chatChannelId)==null)LOG.error("Канал чата {} не найден или нет доступа",cfg.chatChannelId);
        guild.upsertCommand("list","Показать игроков онлайн на Minecraft-сервере").queue(
                ok->LOG.info("Slash-команда /list зарегистрирована"),
                err->LOG.error("Не удалось зарегистрировать /list: {}",err.toString()));

        ChannelSender chatSender=new ChannelSender("chat",jda,cfg.chatChannelId,1000);
        BridgeServer server;
        try{server=new BridgeServer(cfg,inbox,chatSender,listener::isConnected);server.start();}
        catch(Exception e){LOG.error("Не удалось запустить HTTP API на {}:{}: {}",cfg.bridgeBind,cfg.bridgePort,e.toString());chatSender.close();jda.shutdownNow();System.exit(1);return;}
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            LOG.info("Остановка бота...");server.stop();chatSender.close();jda.shutdown();
        },"vanilalive-shutdown"));
        LOG.info("Vanila Live Bot запущен. Minecraft: {}:{}",cfg.minecraftHost,cfg.minecraftPort);
    }
}
