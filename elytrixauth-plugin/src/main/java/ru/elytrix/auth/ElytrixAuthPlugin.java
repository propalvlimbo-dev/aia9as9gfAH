package ru.elytrix.auth;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
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
 * Все сообщения — в messages.yml. Сессии: перезаход с того же IP в течение
 * срока — без пароля. Неавторизованные видят title/actionbar/боссбар-таймер.
 */
public final class ElytrixAuthPlugin extends Plugin {

    private static ElytrixAuthPlugin instance;

    private PluginConfig cfg;
    private Messages messages;
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
        File messagesFile = new File(dataFolder, "messages.yml");
        messages = Messages.load(messagesFile, getLogger());

        try {
            cfg = new PluginConfig(configFile);
        } catch (Exception e) {
            getLogger().severe("Не удалось прочитать config.properties: " + e.getMessage());
            return;
        }

        // Встраиваемая БД (HSQLDB): файл по db.file из config.properties, сервер БД не нужен
        getLogger().info("ElytrixAuth: открываю встроенную БД " + cfg.dbFile() + " ...");
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

        // тик каждые 500 мс: таймер/боссбар, actionbar, опрос 2FA и привязки
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
        getLogger().info("Сессии: " + (cfg.sessionsEnabled() ? "вкл"
                + " (срок " + (cfg.sessionMaxSeconds() / 3600) + " ч, проверка IP: " + cfg.sessionCheckIp() + ")"
                : "выкл"));
        try {
            Class.forName("net.md_5.bungee.protocol.packet.BossBar");
            getLogger().info("BossBar: пакет найден — таймер на полосе здоровья будет работать.");
        } catch (Throwable t) {
            getLogger().warning("BossBar: пакет не найден на этом прокси — время авторизации "
                    + "будет показываться в actionbar над хотбаром.");
        }
        getLogger().info("API-секрет для бота (в .env бота API_KEY): " + cfg.apiSecret());
    }

    @Override
    public void onDisable() {
        // снять боссбары у всех, кто ещё в сессиях
        for (AuthSession s : sessions.values()) {
            if (s.bar != null) {
                s.bar.remove();
                s.bar = null;
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (api != null) {
            api.stop();
        }
        if (db != null) {
            try {
                db.close();
                getLogger().info("ElytrixAuth выключен: БД сохранена (SHUTDOWN COMPACT), HTTP API остановлен.");
            } catch (RuntimeException e) {
                getLogger().warning("Ошибка при закрытии БД: " + e.getMessage());
            }
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

    public Messages messages() {
        return messages;
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
            s.sessionDropped = false;
        }
        return s;
    }

    public void leave(UUID uuid) {
        AuthSession s = sessions.remove(uuid);
        if (s != null && s.bar != null) {
            s.bar.remove();
            s.bar = null;
        }
    }

    /** Показывает экранные подсказки и запускает боссбар с таймером.
     *  Бар пересоздаётся: после смены сервера клиент мог потерять «add». */
    public void showAuthUi(AuthSession s) {
        ProxiedPlayer p = getProxy().getPlayer(s.uuid);
        if (p == null) {
            return;
        }
        int total = Math.max(1, s.totalSec > 0 ? s.totalSec : cfg.loginTimeout());
        if (s.bar != null) {
            s.bar.remove();
            s.bar = null;
        }
        s.barText = null;
        s.bar = Visual.startBossBar(p, messages().raw("bossbar-auth", "sec", String.valueOf(total)));
        if (s.bar != null) {
            s.bar.update(1f, null);
        }
    }

    /** Пометка авторизованным. */
    public void markAuthed(AuthSession s) {
        s.state = AuthSession.State.OK;
        s.deadline = 0;
        s.requestId = -1;
        s.authedAt = now();
        s.tgHintShown = false;
        s.remindAt = 0;
        if (s.bar != null) {
            s.bar.remove();
            s.bar = null;
        }
        s.barText = null;
        ProxiedPlayer p = getProxy().getPlayer(s.uuid);
        if (p != null) {
            Visual.clearTitle(p);
            messages().actionbar(p, "actionbar-authed", "player", s.nickname);
        }
    }

    /** Перевод на целевой сервер после входа (если он есть в конфиге прокси).
     *  С защитой от двойного запроса: если игрок уже на target или connect
     *  к target был инициирован < 2.5 сек назад — повторно не шлём. */
    public void connectTarget(ProxiedPlayer p) {
        try {
            if (p == null || !p.isConnected()) {
                return;
            }
            ServerInfo target = getProxy().getServerInfo(cfg.targetServer());
            if (target == null) {
                return;
            }
            Server cur = p.getServer();
            if (cur != null && cur.getInfo() != null
                    && cur.getInfo().getName().equalsIgnoreCase(target.getName())) {
                return; // уже на target
            }
            AuthSession s = sessions.get(p.getUniqueId());
            long ms = System.currentTimeMillis();
            if (s != null) {
                if (ms - s.lastConnectAt < 2500) {
                    return; // connect уже идёт
                }
                s.lastConnectAt = ms;
            }
            p.connect(target);
        } catch (Throwable ignored) {
        }
    }

    /** Если авторизованный игрок сидит на auth-сервере — переводим его на target. */
    public void ensureNotAuth(ProxiedPlayer p) {
        try {
            Server cur = p.getServer();
            ServerInfo curInfo = cur == null ? null : cur.getInfo();
            ServerInfo target = getProxy().getServerInfo(cfg.targetServer());
            if (target == null) {
                return;
            }
            if (curInfo != null && curInfo.getName().equalsIgnoreCase(target.getName())) {
                return; // уже на target
            }
            ServerInfo auth = authServerInfo();
            if (curInfo == null || (auth != null
                    && curInfo.getName().equalsIgnoreCase(auth.getName()))) {
                connectTarget(p);
            }
        } catch (Throwable ignored) {
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

    /**
     * Периодический тик: кики по таймеру, прогресс/текст боссбара, actionbar,
     * опрос статусов 2FA и привязки Telegram.
     */
    private void tick() {
        long now = now();
        for (AuthSession s : sessions.values()) {
            ProxiedPlayer p = getProxy().getPlayer(s.uuid);
            if (p == null || !p.isConnected()) {
                leave(s.uuid);
                continue;
            }
            switch (s.state) {
                case WAIT:
                    tickWait(s, p, now);
                    break;
                case TG:
                    tickTg(s, p, now);
                    break;
                case OK:
                    tickOk(s, p);
                    break;
            }
        }
    }

    private void tickWait(AuthSession s, ProxiedPlayer p, long now) {
        if (s.deadline > 0) {
            long left = s.deadline - now;
            if (left <= 0) {
                messages().kick(p, s.needReg ? "kick-timeout-reg" : "kick-timeout-login");
                return;
            }
            updateBar(s, p, left);
            long ms = System.currentTimeMillis();
            // периодическое напоминание прямо в чат (раз в ~10 сек, не чаще)
            if (ms - s.remindAt >= 10_000) {
                s.remindAt = ms;
                messages().chat(p, s.needReg ? "remind-reg" : "remind-login",
                        "sec", String.valueOf(left));
            }
            // подсказка над хотбаром (раз в 2 сек) — видна при выключенном чате
            if (ms - s.lastTipAt >= 2000) {
                s.lastTipAt = ms;
                String tip = messages().raw(s.needReg ? "actionbar-reg" : "actionbar-login");
                if (s.bar == null) {
                    // боссбар недоступен — время показываем прямо в actionbar
                    tip += " &8• &f" + left + "&7 сек";
                }
                Visual.actionbar(p, tip);
            }
        }
    }

    private void tickTg(AuthSession s, ProxiedPlayer p, long now) {
        if (s.deadline > 0 && now >= s.deadline) {
            messages().kick(p, "kick-timeout-login");
            return;
        }
        long ms = System.currentTimeMillis();
        if (ms - s.lastTipAt >= 2000) {
            s.lastTipAt = ms;
            String tip = messages().raw("actionbar-tg");
            if (s.bar == null && s.deadline > 0) {
                tip += " &8• &f" + Math.max(0, s.deadline - now) + "&7 сек";
            }
            Visual.actionbar(p, tip);
        }
        updateBar(s, p, Math.max(0, s.deadline - now));

        String st = db.pollLoginRequest(s.requestId);
        if ("confirmed".equals(st)) {
            // сессия выдаётся только ПОСЛЕ подтверждения 2FA (иначе её можно обойти)
            if (cfg.sessionsEnabled()) {
                try {
                    java.util.Optional<Database.PlayerRow> r = db.findPlayer(s.nickname);
                    UUID accountUuid = r.map(x -> x.uuid).orElse(s.uuid);
                    db.updateSession(accountUuid, s.ip, now + cfg.sessionMaxSeconds());
                } catch (SQLException e) {
                    getLogger().severe("updateSession(2fa) error: " + e.getMessage());
                }
            }
            markAuthed(s);
            messages().chatList(p, "tg-confirmed", "player", s.nickname);
            connectTarget(p);
        } else if ("denied".equals(st)) {
            messages().kick(p, "kick-denied");
        } else if ("expired".equals(st)) {
            s.state = AuthSession.State.WAIT;
            s.requestId = -1;
            s.deadline = now + cfg.loginTimeout();
            s.totalSec = cfg.loginTimeout();
            messages().chatList(p, "tg-expired");
            // возвращаем интерфейс входа
            if (s.bar != null) {
                s.bar.remove();
                s.bar = null;
            }
            showAuthUi(s);
        }
    }

    private void tickOk(AuthSession s, ProxiedPlayer p) {
        long now = now();
        // одноразовая подсказка про /addtg — только после входа и если TG не привязан
        if (!s.tgHintShown && now - s.authedAt >= 3) {
            s.tgHintShown = true;
            try {
                Database.PlayerRow r = db.findPlayer(s.nickname).orElse(null);
                if (r != null && r.tgId == null && s.linkId < 0) {
                    messages().chatList(p, "hint-tg");
                }
            } catch (Exception ex) {
                pluginLogWarning("hint-tg: " + ex.getMessage());
            }
        }
        if (s.linkId >= 0 && db.isLinkBound(s.linkId)) {
            s.linkId = -1;
            s.linkCode = null;
            messages().chatList(p, "tg-linked", "player", s.nickname);
        }
    }

    private void pluginLogWarning(String msg) {
        try {
            getLogger().warning(msg);
        } catch (Throwable ignored) {
        }
    }

    /** Прогресс-бар с таймером: текст раз в секунду, здоровье — непрерывно. */
    private void updateBar(AuthSession s, ProxiedPlayer p, long leftSec) {
        Visual.BossBar bar = s.bar;
        if (bar == null) {
            return;
        }
        int sec = (int) Math.max(0, leftSec);
        String key = s.state == AuthSession.State.TG ? "bossbar-tg" : "bossbar-auth";
        String text = messages().raw(key, "sec", String.valueOf(sec));
        float health = 1f;
        if (s.totalSec > 0) {
            health = (float) Math.max(0.0, Math.min(1.0, (double) sec / s.totalSec));
        }
        if (!text.equals(s.barText)) {
            s.barText = text;
            bar.update(health, text);
        } else {
            bar.update(health, null);
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

    /** Сколько неверных попыток осталось у ключа (для подсказки в сообщении). */
    public int failLeft(String key) {
        long[] c = failCounters.get(key);
        if (c == null) {
            return cfg.maxTries();
        }
        long now = now();
        if (now - c[1] > cfg.tryWindow()) {
            failCounters.remove(key);
            return cfg.maxTries();
        }
        return Math.max(0, cfg.maxTries() - (int) c[0]);
    }

    public static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    public ProxyServer proxy() {
        return getProxy();
    }
}
