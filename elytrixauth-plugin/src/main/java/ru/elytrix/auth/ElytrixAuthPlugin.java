package ru.elytrix.auth;

import net.md_5.bungee.api.CommandSender;
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
    /** ip -> epoch-сек окончания временного бана (за перебор пароля). */
    private final Map<String, Long> ipBans = new ConcurrentHashMap<>();
    /** ожидающие подтверждения админ-сбросы: ник(lower) -> [kind(0=full,1=pass), expiry_ms]. */
    private final Map<String, long[]> pendingAdminResets = new ConcurrentHashMap<>();
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
            loadIpBans();
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
        pm.registerCommand(this, new CmdAdmin(this));
        pm.registerCommand(this, new CmdLogout(this));
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
        api = new ApiServer(this, cfg, db, getLogger());
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
        s.lastTitleAt = 0;
        s.joinUiShown = false;
        s.welcomeScheduled = false;
        // первое периодическое напоминание — только через ~10 сек после входа,
        // чтобы не дублировать приветствие при заходе на сервер
        s.remindAt = System.currentTimeMillis();
        return s;
    }

    public void leave(UUID uuid) {
        AuthSession s = sessions.remove(uuid);
        if (s != null && s.bar != null) {
            s.bar.remove();
            s.bar = null;
        }
    }

    /** Выполнить задачу в фоне (поток плагина), чтобы не вешать тик-поток. */
    public void runAsync(Runnable r) {
        if (executor != null && !executor.isShutdown()) {
            executor.execute(r);
        } else {
            r.run();
        }
    }

    /** Выполнить задачу через задержку (мс) на потоке плагина. */
    public void runLater(long delayMs, Runnable r) {
        try {
            if (executor != null && !executor.isShutdown()) {
                executor.schedule(r, delayMs, TimeUnit.MILLISECONDS);
                return;
            }
        } catch (Throwable ignored) {
        }
        r.run();
    }

    /**
     * «Добро пожаловать» в actionbar — примерно через 1 сек после того, как игрок
     * оказался в игровом мире (target.server). Запускается из onServerConnected
     * (событие о подключении к серверу), а показ проверяет, что игрок уже на target.
     */
    public void scheduleWelcome(ProxiedPlayer p, AuthSession s) {
        try {
            if (s == null || p == null || !p.isConnected() || s.welcomeScheduled) {
                return;
            }
            s.welcomeScheduled = true;
            String nick = s.nickname;
            runLater(1000, () -> {
                try {
                    if (!p.isConnected()) {
                        return;
                    }
                    if (sessions.get(p.getUniqueId()) != s || !s.isAuthed()) {
                        return;
                    }
                    ServerInfo cur = p.getServer() == null ? null : p.getServer().getInfo();
                    if (cur == null || !cur.getName().equalsIgnoreCase(cfg.targetServer())) {
                        return; // ещё не в игровом мире — welcome не показываем
                    }
                    messages().actionbar(p, "actionbar-authed", "player", nick);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * Кикнуть игрока через задержку (мс). Нужно для /logout: мгновенный кик +
     * мгновенный реконнект могут попасть в «уже подключен» на прокси (старая
     * сессия ещё не закрыта), а login-кик на некоторых прокси ломает клиент 1.21.4.
     */
    public void kickLater(ProxiedPlayer p, long delayMs, String key, String... args) {
        if (p == null || executor == null || executor.isShutdown()) {
            return;
        }
        try {
            executor.schedule(() -> {
                if (p.isConnected()) {
                    messages().kick(p, key, args);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
    }

    /** Полная перезагрузка: config.properties, messages.yml, БД, HTTP API. */
    public void reloadPlugin() {
        File dataFolder = getDataFolder();
        File configFile = new File(dataFolder, "config.properties");
        PluginConfig.saveDefaultConfig(configFile);
        try {
            cfg = new PluginConfig(configFile);
        } catch (Exception e) {
            getLogger().severe("reload: не удалось прочитать config.properties: " + e.getMessage());
        }
        messages = Messages.load(new File(dataFolder, "messages.yml"), getLogger());
        try {
            if (api != null) {
                api.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (db != null) {
                db.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            db = new Database(dataFolder, cfg, getLogger());
            loadIpBans();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "reload: не удалось открыть БД: " + e.getMessage(), e);
        }
        api = new ApiServer(this, cfg, db, getLogger());
        try {
            api.start();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "reload: не удалось запустить HTTP API на порту "
                    + cfg.apiPort() + ": " + e.getMessage(), e);
        }
        failCounters.clear();
        pendingAdminResets.clear();
        getLogger().info("ElytrixAuth перезагружен (config, messages, БД, HTTP API).");
    }

    /**
     * Админ-сброс с подтверждением: первую команду запоминаем, реально сбрасываем
     * только если админ повторил её в течение 15 секунд.
     * @param passwordOnly true = только пароль, false = пароль + Telegram.
     */
    public void handleAdminReset(CommandSender sender, String nick, boolean passwordOnly) {
        String key = nick.toLowerCase(java.util.Locale.ROOT);
        int kind = passwordOnly ? 1 : 0;
        long ms = System.currentTimeMillis();
        long[] pending = pendingAdminResets.get(key);
        if (pending == null || ms > pending[1] || pending[0] != kind) {
            pendingAdminResets.put(key, new long[]{kind, ms + 15_000});
            messages().sendComp(sender, "admin-confirm-reset", "player", nick,
                    "action", passwordOnly ? "сброс пароля" : "полный сброс");
            return;
        }
        pendingAdminResets.remove(key);

        Database.PlayerRow row;
        try {
            row = db.findPlayerCi(nick).orElse(null);
        } catch (Exception e) {
            row = null;
        }
        if (row == null) {
            messages().sendComp(sender, "admin-player-not-found", "player", nick);
            return;
        }
        try {
            if (passwordOnly) {
                db.adminResetPassword(row.uuid);
            } else {
                db.adminResetAccount(row.uuid, now());
            }
        } catch (SQLException e) {
            getLogger().severe("adminReset error: " + e.getMessage());
            messages().sendComp(sender, "db-error");
            return;
        }
        // снимаем игрока с сети, если он онлайн — пусть зайдёт и создаст новый пароль
        ProxiedPlayer online = getProxy().getPlayer(row.uuid);
        if (online != null) {
            leave(row.uuid);
            messages().kick(online, "admin-kick-reset");
        }
        messages().sendComp(sender, passwordOnly ? "admin-resetpass-done" : "admin-reset-done",
                "player", row.nickname);
    }

    /** Title на экране в зависимости от состояния (ре-показ, чтобы не исчезал). */
    private void showAuthTitle(AuthSession s, ProxiedPlayer p) {
        try {
            if (s.state == AuthSession.State.TG) {
                Visual.title(p, messages().raw("join-title-login"), messages().raw("title-subtitle-tg"));
            } else if (s.needReg) {
                Visual.title(p, messages().raw("join-title-reg"), messages().raw("join-subtitle-reg"));
            } else {
                Visual.title(p, messages().raw("join-title-login"), messages().raw("join-subtitle-login"));
            }
        } catch (Throwable ignored) {
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
        s.okTipAt = 0;
        s.linkDoneAt = 0;
        if (s.bar != null) {
            s.bar.remove();
            s.bar = null;
        }
        s.barText = null;
        ProxiedPlayer p = getProxy().getPlayer(s.uuid);
        // «Добро пожаловать» НЕ шлём здесь — оно показывается в actionbar через
        // 2 секунды после перевода в игровой мир (scheduleWelcome). Убираем только
        // старый title (клиент уже в PLAY — для 1.20.2+ это безопасно, т.к. сюда
        // попадаем только когда игрок уже на сервере).
        if (p != null && p.getServer() != null) {
            Visual.clearTitle(p);
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

    /** Шаг кадров анимации «проверки пароля», мс. */
    private static final long CHECK_STEP_MS = 300;

    /**
     * Красивая анимация «Проверка пароля» в title после успешного /reg или /login
     * (чисто визуальный ход — по факту пароль уже проверен). Точки-прогресс по
     * одной загораются зелёным, затем короткий title успеха, и только потом —
     * перевод на target, чтобы игрок успел увидеть анимацию на auth-сервере.
     *
     * @param doneTitleKey ключ messages.yml с финальным title («Вход выполнен» и т.п.)
     */
    public void playCheckAnimation(ProxiedPlayer p, String doneTitleKey) {
        if (p == null || !p.isConnected() || executor == null || executor.isShutdown()) {
            return;
        }
        try {
            String t = messages().raw("check-title");
            String[] steps = new String[]{
                    messages().raw("check-step-0"),
                    messages().raw("check-step-1"),
                    messages().raw("check-step-2"),
                    messages().raw("check-step-3")
            };
            String doneSub = messages().raw("check-step-3");
            for (int i = 0; i < steps.length; i++) {
                final int idx = i;
                executor.schedule(() -> {
                    if (p.isConnected()) {
                        Visual.title(p, t, steps[idx]);
                    }
                }, i * CHECK_STEP_MS, TimeUnit.MILLISECONDS);
            }
            long endMs = (long) steps.length * CHECK_STEP_MS;
            // финальный «успех»: короткий (fade 1 + stay 12 + fade 5 тиков ≈ 0.9 сек),
            // чтобы не висел на экране, и убирается ровно перед переводом на target
            executor.schedule(() -> {
                if (p.isConnected()) {
                    Visual.title(p, messages().raw(doneTitleKey), doneSub, 1, 12, 5);
                }
            }, endMs, TimeUnit.MILLISECONDS);
            executor.schedule(() -> {
                if (p.isConnected()) {
                    Visual.clearTitle(p);
                    connectTarget(p);
                }
            }, endMs + 900, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // если пул уже закрыт (reload/выключение) — просто пускаем игрока сразу
            connectTarget(p);
        }
    }

    public ServerInfo authServerInfo() {
        return getProxy().getServerInfo(cfg.authServer());
    }

    /**
     * Уведомление в Telegram о входе, когда 2FA у аккаунта ВЫКЛЮЧЕНА:
     * игрока пускаем сразу, а бот присылает сообщение «такой-то вошёл».
     */
    public void notifyTgLogin(Database.PlayerRow row, String ip) {
        try {
            if (row == null || row.tgId == null) {
                return;
            }
            String time = new java.text.SimpleDateFormat("dd.MM.yy HH:mm").format(new java.util.Date());
            String ipPart = (ip == null || ip.isEmpty()) ? "?" : ip;
            db.createAlert(row.tgId,
                    "\uD83D\uDD13 Вход в аккаунт \u00AB" + row.nickname + "\u00BB\n"
                            + "\uD83C\uDF10 IP: " + ipPart + "\n"
                            + "\uD83D\uDD50 " + time,
                    now());
        } catch (SQLException e) {
            getLogger().warning("createAlert(login) error: " + e.getMessage());
        }
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
            if (p.getServer() == null) {
                // Игрок ещё не заведён ни на один сервер. Для клиентов 1.20.2+
                // это значит: LoginSuccess ещё НЕ отправлен — клиент в состоянии
                // LOGIN, и ЛЮБЫЕ пакеты UI (чат/actionbar/title/bossbar) сейчас
                // сломают вход ("login_disconnect ... extra bytes"). Разрешён
                // только кик по таймауту (login-кик — штатный пакет этой фазы).
                if (s.deadline > 0 && now >= s.deadline) {
                    messages().kick(p, s.needReg ? "kick-timeout-reg" : "kick-timeout-login");
                }
                continue;
            }
            // Дополнительная защита: неавторизованный игрок не должен находиться
            // ни на каком сервере, кроме auth. В обычном потоке это исключено
            // (ServerConnectEvent всегда ведёт на auth), но если что-то пошло не так
            // (гонка, сторонний плагин-редирект) — сразу кикаем с понятной причиной.
            if (!s.isAuthed()) {
                ServerInfo curInfo = p.getServer().getInfo();
                ServerInfo authInfo = authServerInfo();
                if (authInfo != null && curInfo != null
                        && !curInfo.getName().equalsIgnoreCase(authInfo.getName())) {
                    messages().kick(p, "kick-need-auth");
                    continue;
                }
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
            // title не убираем с экрана: раз в ~5 сек показываем заново
            if (ms - s.lastTitleAt >= 5000) {
                s.lastTitleAt = ms;
                showAuthTitle(s, p);
            }
            // периодическое напоминание прямо в чат (раз в ~10 сек, не чаще)
            if (ms - s.remindAt >= 10_000) {
                s.remindAt = ms;
                messages().chat(p, s.needReg ? "remind-reg" : "remind-login",
                        "sec", String.valueOf(left));
            }
            // подсказка над хотбаром (раз в 1 сек) — видна при выключенном чате
            if (ms - s.lastTipAt >= 1000) {
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
        if (ms - s.lastTipAt >= 1000) {
            s.lastTipAt = ms;
            String tip = messages().raw("actionbar-tg");
            if (s.bar == null && s.deadline > 0) {
                tip += " &8• &f" + Math.max(0, s.deadline - now) + "&7 сек";
            }
            Visual.actionbar(p, tip);
        }
        // title держим на экране и при ожидании Telegram
        if (ms - s.lastTitleAt >= 5000) {
            s.lastTitleAt = ms;
            showAuthTitle(s, p);
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
            playCheckAnimation(p, "check-done-tg");
        } else if ("denied".equals(st)) {
            messages().kick(p, "kick-denied");
        } else if ("expired".equals(st)) {
            s.state = AuthSession.State.WAIT;
            s.requestId = -1;
            s.deadline = now + cfg.loginTimeout();
            s.totalSec = cfg.loginTimeout();
            messages().chatList(p, "tg-expired");
            // возвращаем интерфейс входа (боссбар, title, actionbar)
            if (s.bar != null) {
                s.bar.remove();
                s.bar = null;
            }
            s.lastTitleAt = 0;
            showAuthUi(s);
            showAuthTitle(s, p);
        }
    }

    private void tickOk(AuthSession s, ProxiedPlayer p) {
        long now = now();
        long ms = System.currentTimeMillis();
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
        // процесс привязки Telegram в actionbar: код + сколько осталось до удаления
        if (s.linkId >= 0) {
            if (ms - s.okTipAt >= 1000) {
                s.okTipAt = ms;
                boolean bound = db.isLinkBound(s.linkId); // сам ловит ошибки БД и вернёт false
                if (bound) {
                    s.linkId = -1;
                    s.linkCode = null;
                    s.linkDoneAt = ms;
                    messages().chatList(p, "tg-linked", "player", s.nickname);
                    messages().actionbar(p, "addtg-actionbar-done");
                    return;
                }
                if (s.linkExpires > 0 && now >= s.linkExpires) {
                    // код истёк — просто перестаём показывать процесс
                    s.linkId = -1;
                    s.linkCode = null;
                    return;
                }
                long left = Math.max(0, s.linkExpires - now);
                messages().actionbar(p, "addtg-actionbar",
                        "code", s.linkCode == null ? "" : s.linkCode,
                        "sec", String.valueOf(left));
            }
            return;
        }
        // баннер «успешно привязал» висит в actionbar ~15 секунд
        if (s.linkDoneAt > 0) {
            if (ms - s.linkDoneAt < 15_000) {
                if (ms - s.okTipAt >= 1000) {
                    s.okTipAt = ms;
                    messages().actionbar(p, "addtg-actionbar-done");
                }
            } else {
                s.linkDoneAt = 0;
            }
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

    /** Текущее число неудачных попыток по ключу (в пределах окна). */
    public int failCount(String key) {
        long[] c = failCounters.get(key);
        if (c == null) {
            return 0;
        }
        if (now() - c[1] > cfg.tryWindow()) {
            failCounters.remove(key);
            return 0;
        }
        return (int) c[0];
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

    // ------- временный бан IP за перебор пароля -------

    /** Загрузить активные баны из БД (при старте/перезагрузке). */
    public void loadIpBans() {
        try {
            Map<String, Long> bans = db.allIpBans();
            ipBans.clear();
            ipBans.putAll(bans);
            getLogger().info("Временных банов IP активно: " + ipBans.size());
        } catch (SQLException e) {
            getLogger().warning("loadIpBans error: " + e.getMessage());
        }
    }

    /** Сколько секунд осталось у бана этого IP (0 — не забанен или истёк). */
    public long ipBanLeftSec(String ip) {
        Long until = ipBans.get(ip);
        if (until == null) {
            return 0;
        }
        long left = until - now();
        if (left <= 0) {
            ipBans.remove(ip); // срок вышел — бан сам снимается
            return 0;
        }
        return left;
    }

    /** Забанить IP по настройкам (ban.ip.minutes). true — бан выдан. */
    public boolean banIp(String ip) {
        if (!cfg.ipBanEnabled()) {
            return false;
        }
        long until = now() + cfg.banIpMinutes() * 60L;
        try {
            db.addIpBan(ip, until);
        } catch (SQLException e) {
            getLogger().warning("addIpBan error: " + e.getMessage());
            return false;
        }
        ipBans.put(ip, until);
        getLogger().warning("IP забанен за перебор пароля: " + ip + " на " + cfg.banIpMinutes() + " мин");
        return true;
    }

    public static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    public ProxyServer proxy() {
        return getProxy();
    }
}
