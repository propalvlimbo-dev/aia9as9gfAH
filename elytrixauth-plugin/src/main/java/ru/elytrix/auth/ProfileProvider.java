package ru.elytrix.auth;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Данные профиля для бота: донат (LuckPerms — префикс и группа) и коины
 * (PlayerPoints).
 *
 * LuckPerms умеет работать и на самом прокси: если API есть в рантайме —
 * опрашиваем напрямую (рефлексия, без зависимости при компиляции).
 * PlayerPoints — только Bukkit-плагин, поэтому префикс/группу/коины с
 * игрового сервера получаем через plugin messaging: на игровом сервере
 * (target.server, обычно "grief") должен стоять маленький плагин
 * ElytrixAuthBridge. Если моста нет / сервер пуст / плагины не стоят —
 * метод просто возвращает пустые данные, бот покажет, чего не хватает.
 */
final class ProfileProvider implements Listener {

    private static final String CHANNEL = "elytrix:auth";
    private static final int LP_LOCAL_TIMEOUT_MS = 2000;
    private static final int BRIDGE_TIMEOUT_MS = 4000;

    private final ElytrixAuthPlugin plugin;
    private final Map<UUID, CompletableFuture<ProfileData>> waits = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer;

    /** Ответ моста/локального LP: что удалось достать. */
    static final class ProfileData {
        boolean lp;
        String lpGroup = "";
        String lpPrefix = "";
        boolean pp;
        long coins;
    }

    private static final class LpLocal {
        boolean ok;
        String group;
        String prefix;
    }

    ProfileProvider(ElytrixAuthPlugin plugin) {
        this.plugin = plugin;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "elytrix-profile");
            t.setDaemon(true);
            return t;
        });
    }

    void register() {
        plugin.proxy().registerChannel(CHANNEL);
        plugin.proxy().getPluginManager().registerListener(plugin, this);
    }

    void shutdown() {
        timer.shutdownNow();
        waits.clear();
    }

    /**
     * Полный профиль (донат + коины). Зовётся из потока HTTP API, блокировка
     * допустима: максимум ~lp-локально(2 c) или мост (до 4 c).
     */
    ProfileData profile(UUID uuid) {
        ProfileData d = new ProfileData();

        // 1) LuckPerms локально на прокси (если стоит)
        LpLocal lp = luckPermsLocal(uuid);
        if (lp.ok) {
            d.lp = true;
            d.lpGroup = nz(lp.group);
            d.lpPrefix = nz(lp.prefix);
        }

        // 2) мост на игровой сервер: PlayerPoints (+ LuckPerms, если нет локально)
        ProxiedPlayer relay = pickRelay(uuid);
        if (relay == null) {
            return d; // игровой сервер недоступен/пуст — что есть, то есть
        }
        CompletableFuture<ProfileData> fut = new CompletableFuture<>();
        CompletableFuture<ProfileData> exist = waits.putIfAbsent(uuid, fut);
        if (exist != null) {
            fut = exist;
        } else {
            try {
                relay.sendData(CHANNEL, ("RQ|" + uuid).getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                waits.remove(uuid, fut);
                return d;
            }
            final CompletableFuture<ProfileData> f = fut;
            timer.schedule(() -> {
                if (waits.remove(uuid, f)) {
                    f.complete(new ProfileData());
                }
            }, BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        try {
            ProfileData got = fut.get(BRIDGE_TIMEOUT_MS + 1500, TimeUnit.MILLISECONDS);
            if (got.lp) {
                d.lp = true;
                d.lpGroup = got.lpGroup;
                d.lpPrefix = got.lpPrefix;
            }
            d.pp = got.pp;
            d.coins = got.coins;
        } catch (Throwable ignored) {
            // таймаут — отвечаем тем, что есть
        }
        return d;
    }

    /** Кого использовать как реле запроса к игровому серверу. */
    private ProxiedPlayer pickRelay(UUID uuid) {
        ProxiedPlayer owner = plugin.proxy().getPlayer(uuid);
        if (owner != null && owner.getServer() != null
                && plugin.cfg().targetServer().equalsIgnoreCase(owner.getServer().getInfo().getName())) {
            return owner;
        }
        ServerInfo target = plugin.proxy().getServerInfo(plugin.cfg().targetServer());
        if (target != null) {
            for (ProxiedPlayer p : target.getPlayers()) {
                return p;
            }
        }
        if (owner != null) {
            return owner; // владелец онлайн, но на другом сервере — вдруг там мост
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ приём ответа моста

    @EventHandler
    public void onPluginMessage(PluginMessageEvent e) {
        if (e.getSender() instanceof ProxiedPlayer) {
            return; // сообщения клиентов — не наши
        }
        if (!(e.getSender() instanceof Server)) {
            return;
        }
        if (e.getTag() == null || !CHANNEL.equals(e.getTag())) {
            return;
        }
        try {
            String s = new String(e.getData(), StandardCharsets.UTF_8);
            if (!s.startsWith("RS|")) {
                return;
            }
            String[] p = s.split("\\|", -1);
            if (p.length < 7) {
                return;
            }
            UUID uuid = UUID.fromString(p[1].trim());
            ProfileData d = new ProfileData();
            d.lp = "1".equals(p[2]);
            d.lpPrefix = dec(p[3]);
            d.lpGroup = dec(p[4]);
            d.pp = "1".equals(p[5]);
            d.coins = Long.parseLong(p[6].trim());
            CompletableFuture<ProfileData> f = waits.remove(uuid);
            if (f != null) {
                f.complete(d);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("ProfileProvider: битый ответ моста: " + t);
        }
    }

    // ------------------------------------------------------------------ LuckPerms локально

    /** Группа и префикс LuckPerms (рефлексия). ok=false, если LP нет на прокси. */
    private LpLocal luckPermsLocal(UUID uuid) {
        LpLocal out = new LpLocal();
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object lp = provider.getMethod("get").invoke(null);
            Object userManager = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object future = userManager.getClass().getMethod("loadUser", UUID.class)
                    .invoke(userManager, uuid);
            Object user = ((CompletableFuture<?>) future).get(LP_LOCAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (user == null) {
                return out;
            }
            out.ok = true;
            out.group = (String) user.getClass().getMethod("getPrimaryGroup").invoke(user);
            try {
                Object cached = user.getClass().getMethod("getCachedData").invoke(user);
                Object meta = cached.getClass().getMethod("getMetaData").invoke(cached);
                Object pr = meta.getClass().getMethod("getPrefix").invoke(meta);
                if (pr != null) {
                    out.prefix = (String) pr;
                }
            } catch (Throwable ignored) {
                // префикс не обязателен
            }
            out.prefix = cleanColors(out.prefix);
        } catch (Throwable t) {
            out.ok = false; // LuckPerms не установлен на прокси
        }
        return out;
    }

    /** Убрать цветовые коды (§ и legacy &) из префикса для красивого показа в TG. */
    static String cleanColors(String s) {
        if (s == null) {
            return null;
        }
        String r = s.replaceAll("(?i)&[0-9a-fk-orx]", "")
                .replaceAll("\u00A7[0-9a-fk-orx]", "").trim();
        return r.isEmpty() ? null : r;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
