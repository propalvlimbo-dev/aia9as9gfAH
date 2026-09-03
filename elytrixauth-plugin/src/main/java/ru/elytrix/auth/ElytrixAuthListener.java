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

    private static final Set<String> PRE_AUTH_COMMANDS = new HashSet<>(Arrays.asList(
            "reg", "register", "l", "login"));
    private static final List<String> PRE_AUTH_TAB = Arrays.asList(
            "/login ", "/l ", "/register ", "/reg ");

    private final ElytrixAuthPlugin plugin;

    public ElytrixAuthListener(ElytrixAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent e) {
        ProxiedPlayer p = e.getPlayer();
        String ip = p.getAddress() == null ? "?" : p.getAddress().getHostString();
        long now = ElytrixAuthPlugin.now();

        Database.PlayerRow row = null;
        try {
            row = plugin.db().findPlayer(p.getName()).orElse(null);
        } catch (Exception ex) {
            plugin.getLogger().warning("findPlayer(join) error: " + ex.getMessage());
        }

        AuthSession s = plugin.join(p.getUniqueId(), p.getName(), ip);
        s.totalSec = plugin.cfg().loginTimeout();

        // 1) автовход по активной сессии (тот же IP, срок не истёк)
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
            plugin.messages().chatList(p, "join-msg-reg", "min", String.valueOf(plugin.cfg().minPassword()));
            Visual.title(p, plugin.messages().raw("join-title-reg"),
                    plugin.messages().raw("join-subtitle-reg"));
        } else {
            s.needReg = false;
            if (s.sessionDropped) {
                plugin.messages().chatList(p, "session-ip-changed");
            }
            plugin.messages().chatList(p, "join-msg-login",
                    "player", p.getName(), "timeout", String.valueOf(plugin.cfg().loginTimeout()));
            Visual.title(p, plugin.messages().raw("join-title-login"),
                    plugin.messages().raw("join-subtitle-login"));
        }
        plugin.showAuthUi(s);
    }

    /** Активная сессия → пускаем без пароля. */
    private void autoLogin(ProxiedPlayer p, AuthSession s, Database.PlayerRow row, String ip) {
        plugin.markAuthed(s);
        long expires = ElytrixAuthPlugin.now() + plugin.cfg().sessionMaxSeconds();
        try {
            plugin.db().updateSession(s.uuid, ip, expires);
        } catch (Exception ex) {
            plugin.getLogger().warning("updateSession(auto) error: " + ex.getMessage());
        }
        plugin.messages().chatList(p, "auto-login", "player", p.getName());
        plugin.connectTarget(p);
    }

    /** После фактического подключения к серверу повторяем инструкцию на экране
     *  (auth-сервер мог прислать свой title/respawn, перекрывший наш). */
    @EventHandler
    public void onServerConnected(ServerConnectedEvent e) {
        ProxiedPlayer p = e.getPlayer();
        AuthSession s = plugin.session(p.getUniqueId());
        if (s == null || s.isAuthed()) {
            return;
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
            return; // авторизованным можно всё
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
            return;
        }
        if (e.isCommand()) {
            String cmd = commandName(e.getMessage());
            if (cmd != null && PRE_AUTH_COMMANDS.contains(cmd)) {
                return; // команды авторизации — пропускаем к обработчику
            }
            plugin.messages().chat(p, s != null && s.needReg ? "cmd-blocked-reg" : "cmd-blocked-login");
        } else {
            long now = ElytrixAuthPlugin.now();
            if (s == null || now - s.lastTipAt > 4) {
                if (s != null) {
                    s.lastTipAt = now;
                }
                plugin.messages().chat(p, s != null && s.needReg ? "chat-blocked-reg" : "chat-blocked-login");
            }
        }
        e.setCancelled(true);
    }

    /**
     * Таб: неавторизованному показываем только команды авторизации,
     * чтобы он не видел список серверных команд и ники игроков.
     */
    @EventHandler
    public void onTabComplete(TabCompleteEvent e) {
        Connection sender = e.getSender();
        if (!(sender instanceof ProxiedPlayer)) {
            return;
        }
        ProxiedPlayer p = (ProxiedPlayer) sender;
        AuthSession s = plugin.session(p.getUniqueId());
        if (s == null || s.isAuthed()) {
            return; // авторизованным — обычный таб
        }
        String cursor = e.getCursor() == null ? "" : e.getCursor();
        e.setCancelled(true);
        List<String> out = new ArrayList<>();
        if (cursor.startsWith("/")) {
            String lower = cursor.toLowerCase(Locale.ROOT);
            for (String suggestion : PRE_AUTH_TAB) {
                if (suggestion.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    out.add(suggestion);
                }
            }
        }
        e.getSuggestions().clear();
        e.getSuggestions().addAll(out);
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
