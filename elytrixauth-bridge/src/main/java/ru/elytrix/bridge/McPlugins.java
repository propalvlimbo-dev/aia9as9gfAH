package ru.elytrix.bridge;

import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Рефлексия LuckPerms/PlayerPoints: мост компилируется без них, но в рантайме
 * находит API, если плагины установлены на этом сервере.
 */
final class McPlugins {

    static final class Lp {
        final String group;
        final String prefix;

        Lp(String group, String prefix) {
            this.group = group;
            this.prefix = prefix;
        }
    }

    private McPlugins() {
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Убрать цветовые коды (§ и legacy &) — префикс для Telegram, код не нужен. */
    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String r = s.replaceAll("(?i)&[0-9a-fk-orx]", "")
                .replaceAll("\u00A7[0-9a-fk-orx]", "").trim();
        return r.isEmpty() ? null : r;
    }

    /** Группа и префикс LuckPerms; null, если LP нет/не отвечает. */
    static Lp luckPerms(UUID uuid) {
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object lp = provider.getMethod("get").invoke(null);
            Object userManager = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object future = userManager.getClass().getMethod("loadUser", UUID.class)
                    .invoke(userManager, uuid);
            Object user = ((CompletableFuture<?>) future).get(4, TimeUnit.SECONDS);
            if (user == null) {
                return null;
            }
            String group = (String) user.getClass().getMethod("getPrimaryGroup").invoke(user);
            String prefix = null;
            try {
                Object cached = user.getClass().getMethod("getCachedData").invoke(user);
                Object meta = cached.getClass().getMethod("getMetaData").invoke(cached);
                Object pr = meta.getClass().getMethod("getPrefix").invoke(meta);
                if (pr != null) {
                    prefix = (String) pr;
                }
            } catch (Throwable ignored) {
                // префикс не обязателен — вернём хотя бы группу
            }
            return new Lp(group == null ? "" : group, clean(prefix));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Коины PlayerPoints; null, если PP нет/не отвечает. */
    static Long playerPoints(UUID uuid) {
        try {
            Class<?> cls = Class.forName("org.black_ixx.playerpoints.PlayerPoints");
            Object inst;
            try {
                inst = cls.getMethod("getInstance").invoke(null);
            } catch (NoSuchMethodException e) {
                inst = null;
            }
            Object api;
            try {
                if (inst != null) {
                    api = inst.getClass().getMethod("getAPI").invoke(inst);
                } else {
                    api = cls.getMethod("getAPI").invoke(null); // совсем старые версии
                }
            } catch (NoSuchMethodException e) {
                return null;
            }
            if (api == null) {
                return null;
            }
            Object res = null;
            String[] names = {"lookSync", "look", "lookAsync"};
            for (String name : names) {
                try {
                    Method m = api.getClass().getMethod(name, UUID.class);
                    if (name.equals("lookAsync")) {
                        Object f = m.invoke(api, uuid);
                        res = f instanceof CompletableFuture<?>
                                ? ((CompletableFuture<?>) f).get(4, TimeUnit.SECONDS) : f;
                    } else {
                        res = m.invoke(api, uuid);
                    }
                    break;
                } catch (NoSuchMethodException ignored) {
                    // пробуем следующее имя
                }
            }
            if (res == null) {
                return null;
            }
            if (res instanceof CompletableFuture<?>) {
                Object v = ((CompletableFuture<?>) res).get(4, TimeUnit.SECONDS);
                return toLong(v);
            }
            return toLong(res);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Наигранное время игрока (тики PLAY_ONE_MINUTE); -1, если не доступно. */
    static long playtimeTicks(org.bukkit.entity.Player p) {
        try {
            Object v = p.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE);
            if (v instanceof Number) {
                return ((Number) v).longValue();
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Long toLong(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

}
