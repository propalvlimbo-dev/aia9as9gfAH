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
 * Данные профиля для бота: донат (LuckPerms — префикс и группа), коины
 * (PlayerPoints) и наигранное время.
 *
 * LuckPerms умеет работать и на самом прокси: если API есть в рантайме —
 * опрашиваем напрямую (рефлексия, без зависимости при компиляции).
 * PlayerPoints и статистика наигранного времени — только Bukkit, поэтому их
 * берём с игрового сервера через plugin messaging (мост ElytrixAuthBridge).
 *
 * Ответы кэшируются на 10 минут, а при заходе игрока на игровой сервер
 * профиль «прогревается» в фоне — так бот показывает данные даже когда
 * игрок офлайн / на сервере никого нет (берём последний известный ответ).
 */
final class ProfileProvider implements Listener {

    private static final String CHANNEL = "elytrix:auth";
    private static final int LP_LOCAL_TIMEOUT_MS = 2000;
    private static final int BRIDGE_TIMEOUT_MS = 4000;
    private static final long CACHE_TTL_MS = 10 * 60_000L;

    private final ElytrixAuthPlugin plugin;
    private final Map<UUID, CompletableFuture<ProfileData>> waits = new ConcurrentHashMap<>();
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer;

    /** Ответ моста/локального LP: что удалось достать (+мета для диагностики). */
    static final class ProfileData {
        boolean lp;
        String lpGroup = "";
        String lpPrefix = "";
        boolean pp;
        long coins;
        long playtimeTicks = -1; // -1 = неизвестно

        // мета (для бота, чтобы объяснить, почему данных нет)
        boolean lpProxy;        // LuckPerms нашёлся локально на прокси
        String relay = "none";  // none | ok | owner_off_server
        boolean fromCache;
        long cacheAgeMs = -1;
    }

    private static final class Cached {
        final ProfileData d;
        final long at;

        Cached(ProfileData d, long at) {
            this.d = d;
            this.at = at;
        }
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
        cache.clear();
    }

    /**
     * Профиль: свежий кэш -> сразу; иначе живой опрос (прокси LP и/или мост)
     * с записью в кэш; если мост недоступен — отдаём устаревший кэш.
     */
    ProfileData profile(UUID uuid) {
        ProfileData d = new ProfileData();

        // 1) LuckPerms локально на прокси (если стоит)
        LpLocal lp = luckPermsLocal(uuid);
        d.lpProxy = lp.ok;
        if (lp.ok) {
            d.lp = true;
            d.lpGroup = nz(lp.group);
            d.lpPrefix = nz(lp.prefix);
        }

        // 2) свежий кэш (данные моста/времени) — отдаём как есть
        Cached c = cache.get(uuid);
        long nowMs = System.currentTimeMillis();
        if (c != null && nowMs - c.at < CACHE_TTL_MS) {
            merge(d, c.d);
            d.fromCache = true;
            d.cacheAgeMs = nowMs - c.at;
            return d;
        }

        // 3) живой опрос моста на игровом сервере
        ProfileData got = bridge(uuid);
        if (got != null) {
            merge(d, got);
            d.relay = "ok";
            cache.put(uuid, new Cached(copyOf(d), System.currentTimeMillis()));
            return d;
        }

        // 4) мост недоступен — отдаём устаревший кэш, если был
        if (c != null) {
            merge(d, c.d);
            d.fromCache = true;
            d.cacheAgeMs = nowMs - c.at;
            d.relay = "stale_cache";
            return d;
        }
        return d;
    }

    /** Фоновый «прогрев» профиля (зовётся при заходе игрока на игровой сервер). */
    void warm(UUID uuid) {
        plugin.runAsync(() -> {
            try {
                profile(uuid);
            } catch (Throwable ignored) {
            }
        });
    }

    private static ProfileData copyOf(ProfileData src) {
        ProfileData d = new ProfileData();
        d.lp = src.lp;
        d.lpGroup = src.lpGroup;
        d.lpPrefix = src.lpPrefix;
        d.pp = src.pp;
        d.coins = src.coins;
        d.playtimeTicks = src.playtimeTicks;
        return d;
    }

    /** Переносим данные моста/кэша в итоговый ответ (не затирая локальный LP). */
    private static void merge(ProfileData into, ProfileData from) {
        if (from.lp) {
            into.lp = true;
            into.lpGroup = from.lpGroup;
            into.lpPrefix = from.lpPrefix;
        }
        if (from.pp) {
            into.pp = true;
            into.coins = from.coins;
        }
        if (from.playtimeTicks >= 0) {
            into.playtimeTicks = from.playtimeTicks;
        }
    }

    /** Живой запрос к мосту; null, если до игрового сервера не достучаться. */
    private ProfileData bridge(UUID uuid) {
        ProxiedPlayer relay = pickRelay(uuid);
        if (relay == null) {
            return null;
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
                return null;
            }
            final CompletableFuture<ProfileData> f = fut;
            timer.schedule(() -> {
                if (waits.remove(uuid, f)) {
                    f.complete(null);
                }
            }, BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        try {
            return fut.get(BRIDGE_TIMEOUT_MS + 1500, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Кого использовать как реле запроса к игровому серверу. */
    private ProxiedPlayer pickRelay(UUID uuid) {
        ProxiedPlayer owner = plugin.proxy().getPlayer(uuid);
        if (owner != null && owner.getServer() != null
                && plugin.cfg().targetServer().equalsIgnoreCase(owner.getServer().getInfo().getName())) {
            return owner; // владелец онлайн на игровом сервере — лучший реле
        }
        ServerInfo target = plugin.proxy().getServerInfo(plugin.cfg().targetServer());
        if (target != null) {
            for (ProxiedPlayer p : target.getPlayers()) {
                return p; // любой игрок на игровом сервере
            }
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
            if (p.length < 8) {
                return;
            }
            UUID uuid = UUID.fromString(p[1].trim());
            ProfileData d = new ProfileData();
            d.lp = "1".equals(p[2]);
            d.lpPrefix = dec(p[3]);
            d.lpGroup = dec(p[4]);
            d.pp = "1".equals(p[5]);
            d.coins = Long.parseLong(p[6].trim());
            long pt = Long.parseLong(p[7].trim());
            d.playtimeTicks = pt >= 0 ? pt : -1;
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
