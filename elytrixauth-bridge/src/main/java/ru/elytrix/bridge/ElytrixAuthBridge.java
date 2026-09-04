package ru.elytrix.bridge;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bukkit-мост для ElytrixAuth (прокси).
 *
 * Прокси шлёт на игровой сервер по plugin messaging (канал "elytrix:auth")
 * запрос вида "RQ|uuid" — этот плагин отвечает данными профиля:
 * донат-префикс/группа (LuckPerms) и коины (PlayerPoints). LuckPerms и
 * PlayerPoints читаются рефлексией, поэтому ElytrixAuthBridge собирается и
 * работает без них (если плагина нет — просто скажет "не установлен").
 */
public final class ElytrixAuthBridge extends JavaPlugin implements PluginMessageListener {

    static final String CHANNEL = "elytrix:auth";
    private ExecutorService worker;

    @Override
    public void onEnable() {
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "elytrix-bridge");
            t.setDaemon(true);
            return t;
        });
        try {
            getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
            getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
            getLogger().info("ElytrixAuthBridge: канал " + CHANNEL
                    + " готов (LuckPerms/PlayerPoints для ElytrixAuth).");
        } catch (Throwable t) {
            getLogger().warning("ElytrixAuthBridge: не удалось зарегистрировать канал: " + t);
        }
    }

    @Override
    public void onDisable() {
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!CHANNEL.equals(channel) || player == null || data == null) {
            return;
        }
        String msg = new String(data, StandardCharsets.UTF_8);
        if (!msg.startsWith("RQ|")) {
            return;
        }
        final String uuidStr = msg.substring(3).trim();
        final Player replyTo = player;
        worker.execute(() -> answer(replyTo, uuidStr));
    }

    private void answer(Player replyTo, String uuidStr) {
        String resp;
        try {
            UUID uuid = UUID.fromString(uuidStr);
            McPlugins.Lp lp = McPlugins.luckPerms(uuid);
            Long coins = McPlugins.playerPoints(uuid);
            // наигранное время (statistic PLAY_ONE_MINUTE) доступно только для
            // онлайн-игрока на этом сервере; у офлайн-цели будет -1 (неизвестно)
            long playtime = -1;
            if (replyTo.getUniqueId().equals(uuid)) {
                playtime = McPlugins.playtimeTicks(replyTo);
            }
            resp = "RS|" + uuid + "|" + (lp != null ? 1 : 0) + "|"
                    + McPlugins.enc(lp != null ? orEmpty(lp.prefix) : "") + "|"
                    + McPlugins.enc(lp != null ? orEmpty(lp.group) : "") + "|"
                    + (coins != null ? 1 : 0) + "|" + (coins != null ? coins : 0)
                    + "|" + playtime;
        } catch (Throwable t) {
            resp = "RS|" + uuidStr + "|0|||0|0|-1";
        }
        try {
            replyTo.sendPluginMessage(this, CHANNEL, resp.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            getLogger().warning("ElytrixAuthBridge: ответ не ушёл на прокси: " + t);
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
