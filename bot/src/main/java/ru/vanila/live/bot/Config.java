package ru.vanila.live.bot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Конфигурация бота из .env; переменные окружения имеют приоритет. */
final class Config {
    static final class ConfigException extends Exception {
        ConfigException(String m) { super(m); }
    }
    final String discordToken;
    final long guildId;
    final long chatChannelId;
    final String bridgeSecret;
    final int bridgePort;
    final String bridgeBind;
    final String minecraftHost;
    final int minecraftPort;

    private Config(String token, long guild, long chat, String secret, int port, String bind, String host, int mcPort) {
        discordToken = token; guildId = guild; chatChannelId = chat; bridgeSecret = secret;
        bridgePort = port; bridgeBind = bind; minecraftHost = host; minecraftPort = mcPort;
    }
    List<String> secrets() { return List.of(discordToken, bridgeSecret); }

    static Config load(Path envFile) throws ConfigException {
        Map<String,String> file = new HashMap<>();
        if (Files.isRegularFile(envFile)) {
            try {
                List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
                for (int i=0;i<lines.size();i++) {
                    String line=lines.get(i);
                    if (i==0 && line.startsWith("\uFEFF")) line=line.substring(1);
                    line=line.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq=line.indexOf('='); if (eq<=0) continue;
                    String k=line.substring(0,eq).trim(), v=line.substring(eq+1).trim();
                    if (v.length()>=2 && ((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'")))) v=v.substring(1,v.length()-1);
                    file.put(k,v);
                }
            } catch(IOException e) { throw new ConfigException("Не удалось прочитать "+envFile+": "+e.getMessage()); }
        }
        List<String> errors=new ArrayList<>();
        String token=get(file,"DISCORD_TOKEN","");
        if(token.isBlank()||token.equals("PUT_YOUR_TOKEN_HERE")) errors.add("DISCORD_TOKEN не задан");
        long guild=id(file,"GUILD_ID","1565370345748299957",errors);
        long chat=id(file,"CHAT_CHANNEL_ID","1566008093349773422",errors);
        String secret=get(file,"BRIDGE_SECRET","");
        if(secret.length()<32||secret.startsWith("CHANGE_ME")) errors.add("BRIDGE_SECRET должен быть случайным и не короче 32 символов");
        int port=parsePort(get(file,"PORT",get(file,"BRIDGE_PORT","8765")),"PORT/BRIDGE_PORT",errors);
        int mcPort=parsePort(get(file,"MC_PORT","25750"),"MC_PORT",errors);
        String bind=get(file,"BRIDGE_BIND","0.0.0.0");
        String host=get(file,"MC_HOST","m1.nexuz.ru").trim();
        if(host.isBlank()) errors.add("MC_HOST не задан");
        if(!errors.isEmpty()) throw new ConfigException(" - "+String.join("\n - ",errors));
        return new Config(token,guild,chat,secret,port,bind,host,mcPort);
    }
    private static int parsePort(String value,String key,List<String> errors) {
        try { int p=Integer.parseInt(value); if(p<1||p>65535) throw new NumberFormatException(); return p; }
        catch(NumberFormatException e) { errors.add(key+" должен быть числом 1-65535"); return 8765; }
    }
    private static String get(Map<String,String> file,String key,String def) {
        String e=System.getenv(key); if(e!=null&&!e.isBlank()) return e.trim();
        String f=file.get(key); return f==null||f.isBlank()?def:f;
    }
    private static long id(Map<String,String> file,String key,String def,List<String> errors) {
        try { long v=Long.parseLong(get(file,key,def)); if(v<=0) throw new NumberFormatException(); return v; }
        catch(NumberFormatException e) { errors.add(key+" должен быть числовым Discord ID"); return 0; }
    }
}
