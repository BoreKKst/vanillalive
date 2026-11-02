package ru.vanila.live.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Публичный Minecraft Server List Ping. Не требует изменения установленного плагина. */
final class MinecraftStatus {
    record Status(int online, int max, List<String> sample) {}
    private final String host; private final int port;
    MinecraftStatus(String host,int port) { this.host=host; this.port=port; }

    Status fetch() throws Exception {
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress(host,port),5000); socket.setSoTimeout(5000);
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            DataInputStream in=new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream hb=new ByteArrayOutputStream(); DataOutputStream h=new DataOutputStream(hb);
            writeVarInt(h,0); writeVarInt(h,765); byte[] hostBytes=host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(h,hostBytes.length); h.write(hostBytes); h.writeShort(port); writeVarInt(h,1); writePacket(out,hb.toByteArray());
            ByteArrayOutputStream rb=new ByteArrayOutputStream(); writeVarInt(new DataOutputStream(rb),0); writePacket(out,rb.toByteArray());
            int packetLength=readVarInt(in); if(packetLength<1||packetLength>1_000_000) throw new IllegalStateException("Некорректный ответ сервера");
            if(readVarInt(in)!=0) throw new IllegalStateException("Неожиданный пакет сервера");
            int jsonLength=readVarInt(in); if(jsonLength<1||jsonLength>packetLength) throw new IllegalStateException("Некорректный JSON статуса");
            byte[] data=in.readNBytes(jsonLength); if(data.length!=jsonLength) throw new IllegalStateException("Неполный ответ сервера");
            JsonObject root=JsonParser.parseString(new String(data,StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject players=root.getAsJsonObject("players"); if(players==null) throw new IllegalStateException("Сервер не вернул данные игроков");
            int online=players.has("online")?players.get("online").getAsInt():0;
            int max=players.has("max")?players.get("max").getAsInt():0;
            List<String> names=new ArrayList<>();
            JsonArray sample=players.has("sample")&&players.get("sample").isJsonArray()?players.getAsJsonArray("sample"):new JsonArray();
            for(JsonElement el:sample) if(el.isJsonObject()&&el.getAsJsonObject().has("name")) {
                String n=Text.clean(el.getAsJsonObject().get("name").getAsString(),32); if(!n.isBlank()&&!names.contains(n)) names.add(n);
            }
            return new Status(online,max,List.copyOf(names));
        }
    }
    private static void writePacket(DataOutputStream out,byte[] data)throws Exception { writeVarInt(out,data.length); out.write(data); out.flush(); }
    private static void writeVarInt(DataOutputStream out,int v)throws Exception { while((v&0xFFFFFF80)!=0){out.writeByte((v&0x7F)|0x80);v>>>=7;}out.writeByte(v); }
    private static int readVarInt(DataInputStream in)throws Exception { int value=0,pos=0; while(true){int b=in.readUnsignedByte();value|=(b&0x7F)<<pos;if((b&0x80)==0)return value;pos+=7;if(pos>=32)throw new IllegalStateException("VarInt слишком длинный");} }
}
