package ru.elytrix.auth;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * ElytrixAuth — авторизация на прокси (NullCordX / Waterfall / BungeeCord).
 * /reg /register, /login /l, привязка Telegram /addtg + 2FA через бота.
 * Хранилище: MariaDB (общая с Telegram-ботом).
 */
public final class ElytrixAuthPlugin extends Plugin {

    public static final String PREFIX = "§8[§bElytrix§8] §7";
    public static final String ERR = "§8[§bElytrix§8] §c";

    private static ElytrixAuthPlugin instance;

    private PluginConfig cfg;
    private Database db;
    private ApiServer api;
    private ScheduledExecutorService executor;

    private final Map<UUID, AuthSession> sessions = new ConcurrentHashMap<>();
    /** ip/nick -> [попытки, окно_старт_epoch] для лимита неверных паролей. */
    private final Map<String, long[]> failCounters = new ConcurrentHashMap<>();
    private boolean authServerMissingLogged = false;

    @Override
    public void onEnable() {
        instance = this;

        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().severe("Не удалось создать папку плагина: " + dataFolder);
            return;
        }
        File configFile = new File(dataFolder, "config.properties");
        PluginConfig.saveDefaultConfig(configFile);

        try {
            cfg = new PluginConfig(configFile);
        } catch (Exception e) {
            getLogger().severe("Не удалось прочитать config.properties: " + e.getMessage());
            return;
        }

        // Встраиваемая БД (HSQLDB): файл по db.file из config.properties, сервер БД не нужен
        try {
            db = new Database(dataFolder, cfg, getLogger());
            getLogger().info("Встроенная БД готова: " + cfg.dbFile() + " (таблицы созданы).");
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Не удалось инициализировать встроенную БД: " + e.getMessage(), e);
            return;
        }

        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "elytrixauth");
            t.setDaemon(true);
            return t;
        });

        PluginManager pm = getProxy().getPluginManager();
        pm.registerCommand(this, new CmdRegister(this));
        pm.registerCommand(this, new CmdLogin(this));
        pm.registerCommand(this, new CmdAddTg(this));
        pm.registerListener(this, new ElytrixAuthListener(this));

        // тик каждые 500 мс: кики по таймеру, опрос 2FA и статуса привязки
        executor.scheduleWithFixedDelay(this::tick, 500, 500, TimeUnit.MILLISECONDS);

        // HTTP API для Telegram-бота
        String apiSecret = cfg.apiSecret();
        if (apiSecret == null || apiSecret.isEmpty() || "CHANGE_ME".equals(apiSecret)) {
            apiSecret = PluginConfig.ensureApiSecret(configFile);
            try {
                cfg = new PluginConfig(configFile); // перечитываем
            } catch (Exception ignored) {
            }
        }
        api = new ApiServer(cfg, db, getLogger());
        try {
            api.start();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Не удалось запустить HTTP API на порту "
                    + cfg.apiPort() + ": " + e.getMessage(), e);
        }
        getLogger().info("ElytrixAuth включён. auth=" + cfg.authServer()
                + ", target=" + cfg.targetServer());
        getLogger().info("API-секрет для бота (в .env бота API_KEY): " + cfg.apiSecret());
    }

    @Override
    public void onDisable() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (api != null) {
            api.stop();
        }
        if (db != null) {
            db.close();
        }
        sessions.clear();
        instance = null;
    }

    public static ElytrixAuthPlugin get() {
        return instance;
    }

    public PluginConfig cfg() {
        return cfg;
    }

    public Database db() {
        return db;
    }

    public AuthSession session(UUID uuid) {
        return sessions.get(uuid);
    }

    public AuthSession join(UUID uuid, String nickname, String ip) {
        AuthSession s = sessions.get(uuid);
        if (s == null) {
            s = new AuthSession(uuid, nickname, ip);
            s.deadline = now() + cfg.loginTimeout();
            sessions.put(uuid, s);
        } else {
            // повторный вход с того же uuid (быстрый реконнект)
            s.state = AuthSession.State.WAIT;
            s.deadline = now() + cfg.loginTimeout();
            s.requestId = -1;
        }
        return s;
    }

    public void leave(UUID uuid) {
        sessions.remove(uuid);
    }

    /** Пометка авторизованным. */
    public void markAuthed(AuthSession s) {
        s.state = AuthSession.State.OK;
        s.deadline = 0;
        s.requestId = -1;
    }

    /** Перевод на целевой сервер после входа (если он есть в конфиге прокси). */
    public void connectTarget(ProxiedPlayer p) {
        ServerInfo target = getProxy().getServerInfo(cfg.targetServer());
        if (target != null) {
            p.connect(target);
        }
    }

    public ServerInfo authServerInfo() {
        return getProxy().getServerInfo(cfg.authServer());
    }

    public boolean isAuthServerMissing() {
        return authServerMissingLogged;
    }

    public void logAuthServerMissing() {
        if (!authServerMissingLogged) {
            authServerMissingLogged = true;
            getLogger().warning("Сервер '" + cfg.authServer() + "' не найден в config.yml прокси!");
        }
    }

    /** Кик по истечении времени на авторизацию / опрос результатов 2FA. */
    private void tick() {
        long now = now();
        for (AuthSession s : sessions.values()) {
            ProxiedPlayer p = getProxy().getPlayer(s.uuid);
            if (p == null) {
                sessions.remove(s.uuid, s);
                continue;
            }
            switch (s.state) {
                case WAIT:
                    if (s.deadline > 0 && now >= s.deadline) {
                        p.disconnect("§cВремя на авторизацию истекло. Зайди снова.");
                    }
                    break;
                case TG: {
                    if (s.deadline > 0 && now >= s.deadline) {
                        p.disconnect("§cВремя на авторизацию истекло. Зайди снова.");
                        break;
                    }
                    String st = db.pollLoginRequest(s.requestId);
                    if ("confirmed".equals(st)) {
                        markAuthed(s);
                        p.sendMessage("§aВход подтверждён в Telegram. Добро пожаловать!");
                        connectTarget(p);
                    } else if ("denied".equals(st)) {
                        p.disconnect("§cВход отклонён в Telegram.");
                    } else if ("expired".equals(st)) {
                        s.state = AuthSession.State.WAIT;
                        s.requestId = -1;
                        p.sendMessage("§cКод подтверждения в Telegram истёк. Введи /login ещё раз.");
                    }
                    break;
                }
                case OK: {
                    if (s.linkId >= 0 && db.isLinkBound(s.linkId)) {
                        s.linkId = -1;
                        s.linkCode = null;
                        p.sendMessage("§aTelegram успешно привязан к аккаунту §f" + s.nickname + "§a!");
                        p.sendMessage("§7Теперь при входе после пароля нужно будет подтверждать вход в Telegram.");
                    }
                    break;
                }
            }
        }
    }

    // ------- защита от перебора пароля -------

    public boolean isFailBlocked(String key) {
        long[] c = failCounters.get(key);
        if (c == null) {
            return false;
        }
        long now = now();
        if (now - c[1] > cfg.tryWindow()) {
            failCounters.remove(key);
            return false;
        }
        return c[0] >= cfg.maxTries();
    }

    public void registerFail(String key) {
        long now = now();
        failCounters.compute(key, (k, c) -> {
            if (c == null || now - c[1] > cfg.tryWindow()) {
                return new long[]{1, now};
            }
            c[0]++;
            return c;
        });
    }

    public void clearFails(String key) {
        failCounters.remove(key);
    }

    public static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    public ProxyServer proxy() {
        return getProxy();
    }
}
