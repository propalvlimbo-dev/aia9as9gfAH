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

        Database.PlayerRow row = null;
        try {
            row = plugin.db().findPlayer(p.getName()).orElse(null);
        } catch (Exception ex) {
            plugin.getLogger().warning("findPlayer(join) error: " + ex.getMessage());
        }

        AuthSession s = plugin.join(p.getUniqueId(), p.getName(), ip);
        s.totalSec = plugin.cfg().loginTimeout();

        // ВАЖНО: сюда игроку НЕЛЬЗЯ слать ни одного пакета (чат/title/actionbar/
        // bossbar). Для клиентов 1.20.2+ прокси отправляет LoginSuccess только в
        // конце подключения к первому серверу (ServerConnector.cutThrough), т.е.
        // в PostLogin клиент всё ещё в состоянии LOGIN — пакеты UI в этом окне
        // ломают вход ("login_disconnect ... was larger than I expected").
        // Все приветствия/подсказки показываем в onServerConnected (клиент уже
        // в PLAY). Здесь — только состояние и БД (кики-дисконнекты допустимы:
        // login-кик — штатный пакет фазы LOGIN).

        // аккаунт заморожен владельцем (экстренно, через бота) — не пускаем,
        // даже с активной сессией (мягкий кик, как остальные)
        if (row != null && row.frozen) {
            plugin.kickLater(p, 400, "kick-frozen");
            return;
        }

        // 1) автовход по активной сессии (тот же IP, срок не истёк).
        //    При включённой 2FA (кнопка в Telegram) автовход не действует —
        //    всегда нужен пароль + подтверждение.
        if (row != null && row.passwordHash != null && plugin.cfg().sessionsEnabled()
                && !(row.tgId != null && row.tg2fa)
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
    }

    /** Активная сессия → пускаем без пароля (состояние/БД, без пакетов игроку). */
    private void autoLogin(ProxiedPlayer p, AuthSession s, Database.PlayerRow row, String ip) {
        // авто-вход — тоже «вход в аккаунт»: пишем историю, шлём уведомление в TG
        // (уважает настройку «Уведомления»)
        plugin.onSuccessfulLogin(row, ip);
        plugin.markAuthed(s); // визуал внутри markAuthed сам отключится — сервера ещё нет
        long expires = ElytrixAuthPlugin.now() + plugin.cfg().sessionMaxSeconds();
        try {
            plugin.db().updateSession(row.uuid, ip, expires);
        } catch (Exception ex) {
            plugin.getLogger().warning("updateSession(auto) error: " + ex.getMessage());
        }
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
        ProxiedPlayer p = e.getPlayer();
        AuthSession s = plugin.session(p.getUniqueId());
        if (s == null) {
            return;
        }
        s.joinedServerAt = System.currentTimeMillis();
        if (s.isAuthed()) {
            // авто-вход по сессии (или заход на след. сервер после /login).
            // «Добро пожаловать» в actionbar покажет scheduleWelcome — через 2 сек
            // ПОСЛЕ перевода в игровой мир (target), а не на auth.
            if (!s.joinUiShown) {
                s.joinUiShown = true;
                plugin.messages().chatList(p, "auto-login", "player", s.nickname);
            }
            plugin.scheduleWelcome(p, s);
            plugin.ensureNotAuth(p);
            return;
        }
        if (!s.joinUiShown) {
            // первое приземление (на auth): приветствие в зависимости от ситуации
            s.joinUiShown = true;
            if (s.sessionDropped) {
                plugin.messages().chatList(p, "session-ip-changed");
            }
            if (s.needReg) {
                plugin.messages().chatList(p, "join-msg-reg",
                        "min", String.valueOf(plugin.cfg().minPassword()),
                        "timeout", String.valueOf(plugin.cfg().loginTimeout()));
            } else {
                plugin.messages().chatList(p, "join-msg-login",
                        "player", s.nickname,
                        "timeout", String.valueOf(plugin.cfg().loginTimeout()));
            }
        }
        plugin.showAuthUi(s);
        Visual.title(p,
                plugin.messages().raw(s.needReg ? "join-title-reg" : "join-title-login"),
                plugin.messages().raw(s.needReg ? "join-subtitle-reg" : "join-subtitle-login"));
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
        plugin.leave(e.getPlayer().getUniqueId());
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
