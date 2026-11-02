# VanilaLive — Minecraft ↔ Discord

Java 21 bridge: Discord bot + existing Paper plugin. This update changes **bot code only**. The `minecraft-plugin/` source and `dist/VanilaLivePlugin.jar` are not modified.

## What works

- Minecraft chat is sent to the configured Discord chat channel with `[GLOBAL]` label.
- Messages in that same Discord channel are delivered into Minecraft as `[Discord] nickname: message`.
- Discord slash command `/list` requests the Minecraft Java status endpoint and displays online count and the player names the server publishes in its status response.
- No separate console channel and no Pterodactyl API key are required.
- The bridge keeps the existing HMAC authentication between the bot and the installed plugin.

**Important `/list` limitation:** Minecraft Server List Ping commonly publishes only a sample of player names (often up to 12), or no names at all. Therefore `/list` can always show the online count when the server status endpoint is reachable, but it cannot guarantee every online name without adding a player-list endpoint to the Minecraft plugin. The plugin was intentionally left unchanged as requested.

**Chat type limitation:** the current installed plugin sends a plain Paper chat event without a local/global marker. The bot labels received chat as `[GLOBAL]`. True `[LOCAL]` versus `[GLOBAL]` classification requires the plugin/chat-system to send that metadata.

## Configure

Create `.env` next to the bot JAR using `dist/.env.example` as a template:

```env
DISCORD_TOKEN=YOUR_DISCORD_BOT_TOKEN
GUILD_ID=YOUR_DISCORD_SERVER_ID
CHAT_CHANNEL_ID=YOUR_CHAT_CHANNEL_ID
BRIDGE_SECRET=YOUR_RANDOM_SECRET_AT_LEAST_32_CHARACTERS
MC_HOST=m1.nexuz.ru
MC_PORT=25750
BRIDGE_BIND=0.0.0.0
BRIDGE_PORT=8765
```

Use the same `BRIDGE_SECRET` as in `plugins/VanilaLive/config.yml`. Do not share `.env`, tokens, or the secret.

In Discord Developer Portal enable **Message Content Intent**. Invite the bot with View Channel, Send Messages, and Use Application Commands permissions in the target server/channel. The bot registers `/list` on startup; it may take a short moment to appear.

## Render deployment

1. Push this project to GitHub.
2. In Render create a **Web Service** from the repository.
3. Select **Docker** as the runtime; the repository `Dockerfile` builds and starts the bot.
4. Add environment variables from the `.env` list above. Do not set `PORT` manually; Render provides it. `BRIDGE_BIND` must remain `0.0.0.0`.
5. Deploy. Check logs for `Discord подключён`, `Slash-команда /list зарегистрирована`, and `HTTP API моста слушает 0.0.0.0:<PORT>`.
6. Copy the public HTTPS service URL from Render into the existing plugin config:

```yaml
bridge:
  url: "https://YOUR-SERVICE.onrender.com"
  secret: "THE_SAME_BRIDGE_SECRET"
```

Restart the Minecraft server/plugin. The plugin makes outbound HTTPS requests; no inbound port on Nexuz is needed.

## Local build

Requirements: JDK 21 and Maven 3.9+.

```bash
mvn -B -pl bot -am clean package
```

The bot JAR is `bot/target/VanilaLiveBot.jar`. Copy it and `.env` into the same folder, then run:

```bash
java -jar VanilaLiveBot.jar
```

## Discord channel flow

- Minecraft → Discord: same configured chat channel, `[GLOBAL] Player: message`.
- Discord → Minecraft: same channel, `[Discord] Nickname: message`.
- `/list`: slash command in the configured Discord guild. It is not a Minecraft command.

The bridge does not execute Discord messages as Minecraft console commands.
