package ru.elytrix.auth;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.TabCompleteEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Защита соединений: держим неавторизованных на auth-сервере, режем чат и
 * команды, подсказываем, что вводить (title/actionbar/боссбар), не даём
 * светить лишние команды в табе. При активной сессии — пускаем без пароля.
 */
public final class ElytrixAuthListener implements Listener {

    /** Команды авторизации: до входа — единственное, что разрешено; после входа — скрыты/заглушены. */
    private static final Set<String> AUTH_COMMANDS = new HashSet<>(Arrays.asList(
            "reg", "register", "l", "login"));
    /** Таб у неавторизованного: только нужная команда (см. needReg) + заглушка «ПАРОЛЬ» после пробела. */
    private static final List<String> TAB_LOGIN_CMDS = Arrays.asList("/login", "/l");
    private static final List<String> TAB_REG_CMDS = Arrays.asList("/register", "/reg");
    private static final String TAB_PASSWORD = "ПАРОЛЬ"; // визуальная заглушка вместо пароля

    private final ElytrixAuthPlugin plugin;

    public ElytrixAuthListener(ElytrixAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent e) {
        ProxiedPlayer p = e.getPlayer();
        String ip = p.getAddress() == null ? "?" : p.getAddress().getHostString();
        long now = ElytrixAuthPlugin.now();
        plugin.getLogger().info("ElytrixAuth: PostLogin " + p.getName() + " ip=" + ip);

        // временный бан IP (перебор пароля): не пускаем даже с активной сессией.
        // Кик с задержкой: мгновенный разрыв в PostLogin (клиент ещё в фазе LOGIN,
        // LoginSuccess не получен) на ряде клиентов/прокси выглядит как
        // «ошибка сетевого протокола» — даём клиенту дочитать и закрыть поток штатно.
        long banLeft = plugin.ipBanLeftSec(ip);
        if (banLeft > 0) {
            plugin.kickLater(p, 400, "kick-ip-banned",
                    "time", String.valueOf(Math.max(1, (banLeft + 59) / 60)));
            return;
        }

        // анти-мультибокс: не больше online.max.per.ip аккаунтов онлайн с одного IP
        int maxOnline = plugin.cfg().onlineMaxPerIp();
        if (maxOnline > 0) {
            int same = 0;
            for (ProxiedPlayer o : plugin.proxy().getPlayers()) {
                if (o == p) {
                    continue;
                }
                String oip = o.getAddress() == null ? null : o.getAddress().getHostString();
                if (ip.equals(oip) && ++same >= maxOnline) {
                    plugin.kickLater(p, 400, "kick-online-ip-limit",
                            "max", String.valueOf(maxOnline));
                    return;
                }
            }
        }

        AuthSession s = plugin.join(p.getUniqueId(), p.getName(), ip);
        s.totalSec = plugin.cfg().loginTimeout();

        // ВАЖНО: сюда игроку НЕЛЬЗЯ слать ни одного пакета (чат/title/actionbar/
        // bossbar). Для клиентов 1.20.2+ прокси отправляет LoginSuccess только в
        // конце подключения к первому серверу (ServerConnector.cutThrough), т.е.
        // в PostLogin клиент всё ещё в состоянии LOGIN — пакеты UI в этом окне
        // ломают вход. Все приветствия/подсказки показываем в onServerConnected.
        // Здесь — только состояние (кики-дисконнекты допустимы: login-кик — штатный
        // пакет фазы LOGIN).

        // Чтение БД (заморозка/сессия/новичок) делаем В ФОНЕ: HSQLDB на слабом
        // VDS может тормозить 50-150 мс, а блокировать поток событий прокси в
        // момент доводки коннекта до auth нельзя — сервер рвёт соединение
        // («Сервер, на котором вы находились, выключился»). Пока БД отвечает,
        // игрок уже на auth и видит экран; needReg докрутится через ~100 мс.
        final ProxiedPlayer fp = p;
        final String fip = ip;
        final AuthSession fs = s;
        final long fnow = now;
        plugin.runAsync(() -> resolveJoinRow(fp, fs, fip, fnow));
    }

    /** Фоновая часть PostLogin: читает аккаунт из БД и решает судьбу входа. */
    private void resolveJoinRow(ProxiedPlayer p, AuthSession s, String ip, long now) {
        try {
            if (!p.isConnected()) {
                return; // игрок уже отвалился, пока читали БД
            }
            Database.PlayerRow row = plugin.db().findPlayer(p.getName()).orElse(null);

            // аккаунт заморожен владельцем (экстренно, через бота) — не пускаем,
            // даже с активной сессией (мягкий кик, как остальные)
            if (row != null && row.frozen) {
                plugin.kickLater(p, 400, "kick-frozen");
                return;
            }

            // 1) автовход по активной сессии (тот же IP, срок не истёк).
            //    Сессия действует и при включённой 2FA: повторный вход в течение
            //    срока сессии идёт без пароля и без подтверждения в Telegram.
            if (row != null && row.passwordHash != null && plugin.cfg().sessionsEnabled()
                    && row.sessionExpires != null && row.sessionIp != null) {
                boolean sameIp = row.sessionIp.equals(ip);
                if (!plugin.cfg().sessionCheckIp() || sameIp) {
                    if (row.sessionExpires >= now) {
                        autoLogin(p, s, row, ip);
                        return;
                    }
                }
                if (plugin.cfg().sessionCheckIp() && !sameIp) {
                    s.sessionDropped = true; // сессия была, но IP сменился — нужен пароль
                }
            }

            // 2) новичок или вход по паролю
            if (row == null || row.passwordHash == null) {
                s.needReg = true;
            } else {
                s.needReg = false;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("resolveJoinRow error for " + p.getName() + ": " + t);
        }
    }


    /** Активная сессия → пускаем без пароля (состояние/БД, без пакетов игроку). */
    private void autoLogin(ProxiedPlayer p, AuthSession s, Database.PlayerRow row, String ip) {
        plugin.getLogger().info("ElytrixAuth: автовход по сессии " + p.getName() + " (ip=" + ip + ")");
        // авто-вход — тоже «вход в аккаунт»: пишем историю, шлём уведомление в TG
        // (уважает настройку «Уведомления»). onSuccessfulLogin уже работает в фоне.
        plugin.onSuccessfulLogin(row, ip);
        plugin.markAuthed(s); // визуал внутри markAuthed сам отключится — сервера ещё нет
        final UUID uuid = row.uuid;
        final String addr = ip;
        final long expires = ElytrixAuthPlugin.now() + plugin.cfg().sessionMaxSeconds();
        plugin.runAsync(() -> {
            try {
                plugin.db().updateSession(uuid, addr, expires);
            } catch (Exception ex) {
                plugin.getLogger().warning("updateSession(auto) error: " + ex.getMessage());
            }
        });
        // перевод на target произойдёт в ServerConnect/ServerConnected,
        // чтобы игрок не «мелькал» на auth-карте
    }

    /** После фактического подключения к серверу показываем игроку весь интерфейс:
     *  приветствие в чат, title, боссбар. Это ЕДИНСТВЕННОЕ безопасное место для
     *  пакетов игроку после входа: здесь клиент уже получил LoginSuccess и прошёл
     *  configuration (для 1.20.2+ LoginSuccess прокси шлёт только в конце коннекта
     *  к серверу), т.е. он в PLAY. Плюс доводим авто-вход: если авторизованного
     *  всё же занесло на auth — переводим на target. */
    @EventHandler
    public void onServerConnected(ServerConnectedEvent e) {
        long t0 = System.nanoTime();
        ProxiedPlayer p = e.getPlayer();
        AuthSession s = plugin.session(p.getUniqueId());
        String serverName = p.getServer() != null && p.getServer().getInfo() != null
                ? p.getServer().getInfo().getName() : "?";
        if (s == null) {
            plugin.getLogger().info("ElytrixAuth: ServerConnected " + p.getName()
                    + " -> " + serverName + " (нет сессии, пропуск)");
            return;
        }
        s.joinedServerAt = System.currentTimeMillis();
        if (s.isAuthed()) {
            // авто-вход по сессии (или заход на след. сервер после /login).
            // «Добро пожаловать» в actionbar покажет scheduleWelcome — через 2 сек
            // ПОСЛЕ перевода в игровой мир (target), а не на auth.
            plugin.getLogger().info("ElytrixAuth: ServerConnected " + p.getName()
                    + " -> " + serverName + " (авторизован, UI=" + s.joinUiShown + ")");
            if (!s.joinUiShown) {
                s.joinUiShown = true;
                plugin.messages().chatList(p, "auto-login", "player", s.nickname);
            }
            plugin.scheduleWelcome(p, s);
            plugin.ensureNotAuth(p);
            // игрок на игровом сервере — заранее тянем профиль (донат/коины/время)
            // и кладём в кэш, чтобы бот показывал его даже когда игрок офлайн
            if (plugin.cfg().targetServer().equalsIgnoreCase(serverName)) {
                plugin.profileProvider().warm(p.getUniqueId());
            }
            return;
        }
        plugin.getLogger().info("ElytrixAuth: ServerConnected " + p.getName()
                + " -> " + serverName + " (не авторизован, needReg=" + s.needReg
                + ", UI=" + s.joinUiShown + ")");
        if (!s.joinUiShown && !s.uiPending) {
            // Первый показ UI (чат-приветствие, title, боссбар) откладываем:
            // в момент самого переключения на auth (ServerConnected) клиент/прокси
            // ещё доводят коннект, и поток пакетов в этом окне на некоторых форках
            // (NullCordX/FlameCord с защитой от ботов) рвёт соединение. Ждём, пока
            // у игрока реально появится сервер, и только потом шлём пакеты.
            s.uiPending = true;
            final UUID uuid = p.getUniqueId();
            final AuthSession sess = s;
            final boolean needReg = s.needReg;
            final boolean dropped = s.sessionDropped;
            final int total = Math.max(1, s.totalSec > 0 ? s.totalSec : plugin.cfg().loginTimeout());
            scheduleJoinUi(uuid, sess, needReg, dropped, total, 2500, 5);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        plugin.getLogger().info("ElytrixAuth: ServerConnected обработан за " + ms + " мс");
    }

    /**
     * Отложенный показ первого UI (приветствие в чат + боссбар + title).
     * Ждёт, пока у игрока реально появится сервер (getServer() != null) —
     * пакеты клиенту до этого момента могут рвать соединение. Если сервера
     * всё ещё нет — повторяет попытку (до maxTries раз).
     */
    private void scheduleJoinUi(final UUID uuid, final AuthSession sess,
                                final boolean needReg, final boolean dropped,
                                final int total, long delayMs, int maxTries) {
        plugin.runLater(delayMs, () -> {
            try {
                AuthSession cur = plugin.session(uuid);
                if (cur != sess || cur.joinUiShown) {
                    return; // сессия сменилась / UI уже показан
                }
                ProxiedPlayer pp = plugin.proxy().getPlayer(uuid);
                if (pp == null || !pp.isConnected() || cur.isAuthed()) {
                    return;
                }
                if (pp.getServer() == null) {
                    // сервер ещё не назначен — пробуем позже (если есть попытки)
                    if (maxTries > 0) {
                        plugin.getLogger().info("ElytrixAuth: сервер для " + pp.getName()
                                + " ещё не назначен, UI отложен ещё раз");
                        scheduleJoinUi(uuid, sess, needReg, dropped, total, 1500, maxTries - 1);
                    } else {
                        cur.uiPending = false;
                    }
                    return;
                }
                cur.joinUiShown = true;
                cur.uiPending = false;
                if (dropped) {
                    plugin.messages().chatList(pp, "session-ip-changed");
                }
                if (needReg) {
                    plugin.messages().chatList(pp, "join-msg-reg",
                            "min", String.valueOf(plugin.cfg().minPassword()),
                            "timeout", String.valueOf(total));
                } else {
                    plugin.messages().chatList(pp, "join-msg-login",
                            "player", cur.nickname,
                            "timeout", String.valueOf(total));
                }
                plugin.showAuthUi(cur);
                Visual.title(pp,
                        plugin.messages().raw(cur.needReg ? "join-title-reg" : "join-title-login"),
                        plugin.messages().raw(cur.needReg ? "join-subtitle-reg" : "join-subtitle-login"));
            } catch (Throwable t) {
                plugin.getLogger().warning("ElytrixAuth: отложенный UI не показан: " + t);
            }
        });
    }

    @EventHandler
    public void onServerConnect(ServerConnectEvent e) {
        ProxiedPlayer p = e.getPlayer();
        AuthSession s = plugin.session(p.getUniqueId());
        if (s != null && s.isAuthed()) {
            // авторизованный (в т.ч. автовход по сессии): не трогаем маршрут —
            // если его всё же занесло на auth, доведём до target после подключения
            // (onServerConnected), чтобы не было двух конкурирующих connect-запросов
            // и сообщения «Подключение к этому серверу уже выполняется».
            return;
        }
        // неавторизованный: разрешаем только auth-сервер
        ServerInfo target = e.getTarget();
        ServerInfo auth = plugin.authServerInfo();
        if (auth == null) {
            plugin.logAuthServerMissing();
            e.setCancelled(true);
            return;
        }
        if (target == null || !target.getName().equalsIgnoreCase(auth.getName())) {
            e.setTarget(auth);
        }
    }

    @EventHandler
    public void onChat(ChatEvent e) {
        Connection sender = e.getSender();
        if (!(sender instanceof ProxiedPlayer)) {
            return;
        }
        ProxiedPlayer p = (ProxiedPlayer) sender;
        AuthSession s = plugin.session(p.getUniqueId());
        if (s != null && s.isAuthed()) {
            // авторизованному команды входа/регистрации не нужны (особенно на игровом сервере):
            // /login, /l, /register, /reg молча гасим, остальное не трогаем
            if (e.isCommand()) {
                String cmd = commandName(e.getMessage());
                if (cmd != null && AUTH_COMMANDS.contains(cmd)) {
                    e.setCancelled(true);
                }
            }
            return;
        }
        if (e.isCommand()) {
            String cmd = commandName(e.getMessage());
            if (cmd != null && AUTH_COMMANDS.contains(cmd)) {
                return; // команды авторизации — пропускаем к обработчику
            }
            plugin.messages().chat(p, s != null && s.needReg ? "cmd-blocked-reg" : "cmd-blocked-login");
        } else {
            long now = ElytrixAuthPlugin.now();
            if (s == null || now - s.chatTipAt > 5) {
                if (s != null) {
                    s.chatTipAt = now;
                }
                plugin.messages().chat(p, s != null && s.needReg ? "chat-blocked-reg" : "chat-blocked-login");
            }
        }
        e.setCancelled(true);
    }

    /**
     * Таб:
     *  - неавторизованному (на auth-сервере) показываем ТОЛЬКО ту команду,
     *    которая ему нужна: регистрирующемуся — /register, входящему — /login
     *    (с алиасами /reg и /l); после пробела — слово-заглушку «ПАРОЛЬ»;
     *  - авторизованному из подсказок вырезаем команды авторизации,
     *    чтобы они не светились на игровом сервере.
     *
     * Важно: событие НЕ отменяем, если есть подсказки — по реализации BungeeCord
     * ответ клиенту уходит только у неотменённого события с непустым списком.
     * Отменяем лишь когда показывать нечего (чтобы запрос не ушёл на бэкенд
     * и неавторизованный не увидел чужие команды/ников в табе).
     */
    @EventHandler
    public void onTabComplete(TabCompleteEvent e) {
        Connection sender = e.getSender();
        if (!(sender instanceof ProxiedPlayer)) {
            return;
        }
        ProxiedPlayer p = (ProxiedPlayer) sender;
        AuthSession s = plugin.session(p.getUniqueId());
        if (s == null) {
            return; // сессии нет — не трогаем таб
        }
        if (s.isAuthed()) {
            // авторизованный: убираем /login /l /register /reg из того, что прислал прокси
            e.getSuggestions().removeIf(sug -> isAuthSuggestion(sug));
            return;
        }
        String cursor = e.getCursor() == null ? "" : e.getCursor();
        List<String> cmds = s.needReg ? TAB_REG_CMDS : TAB_LOGIN_CMDS;
        List<String> out = new ArrayList<>();
        int sp = cursor.indexOf(' ');
        if (sp < 0) {
            // пробела ещё нет — дописываем нужную команду авторизации
            if (cursor.startsWith("/")) {
                String typed = cursor.toLowerCase(Locale.ROOT);
                for (String cmd : cmds) {
                    if (cmd.startsWith(typed)) {
                        out.add(cmd + " ");
                    }
                }
                // набрана ровно одна команда — соседний алиас не предлагаем
                if (out.size() > 1) {
                    for (String cmd : cmds) {
                        if (cmd.equals(typed)) {
                            out.clear();
                            out.add(cmd + " ");
                            break;
                        }
                    }
                }
            }
        } else {
            // команда уже набрана — подсказываем слово «ПАРОЛЬ» вместо пароля
            String cmd = cursor.substring(0, sp).toLowerCase(Locale.ROOT);
            boolean authCmd = cmd.equals("/login") || cmd.equals("/l")
                    || cmd.equals("/register") || cmd.equals("/reg");
            if (authCmd) {
                String token = cursor.substring(cursor.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
                if ("пароль".startsWith(token)) {
                    out.add(TAB_PASSWORD);
                }
            }
        }
        e.getSuggestions().clear();
        e.getSuggestions().addAll(out);
        if (out.isEmpty()) {
            e.setCancelled(true); // нечего показать — гасим запрос полностью
        }
    }

    /** Подсказка относится к команде авторизации ("login", "/l", "reg " и т.п.). */
    private static boolean isAuthSuggestion(String sug) {
        if (sug == null) {
            return false;
        }
        String t = sug.trim();
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        int sp = t.indexOf(' ');
        String word = (sp > 0 ? t.substring(0, sp) : t).toLowerCase(Locale.ROOT);
        return AUTH_COMMANDS.contains(word);
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent e) {
        ProxiedPlayer p = e.getPlayer();
        AuthSession s = plugin.session(p.getUniqueId());
        // диагностика: если игрок отвалился сразу после подключения, это видно здесь
        if (s != null) {
            String srv = p.getServer() != null && p.getServer().getInfo() != null
                    ? p.getServer().getInfo().getName() : "?";
            long since = s.joinedServerAt == 0 ? -1
                    : System.currentTimeMillis() - s.joinedServerAt;
            plugin.getLogger().info("ElytrixAuth: отключился " + p.getName()
                    + " (сервер=" + srv + ", state=" + s.state
                    + ", на сервере был " + since + " мс, UI=" + s.joinUiShown + ")");
        }
        plugin.leave(p.getUniqueId());
    }

    private static String commandName(String message) {
        if (message == null || !message.startsWith("/") || message.length() < 2) {
            return null;
        }
        String line = message.substring(1);
        int sp = line.indexOf(' ');
        String cmd = sp > 0 ? line.substring(0, sp) : line;
        return cmd.toLowerCase(Locale.ROOT);
    }
}
