package ru.elytrix.auth;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Защита соединений: неавторизованных держим на auth-сервере, режем чат/команды. */
public final class ElytrixAuthListener implements Listener {

    private static final Set<String> ALLOWED_PRE_AUTH_COMMANDS = new HashSet<>(Arrays.asList(
            "reg", "register", "l", "login", "addtg"));

    private final ElytrixAuthPlugin plugin;

    public ElytrixAuthListener(ElytrixAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent e) {
        ProxiedPlayer p = e.getPlayer();
        String ip = p.getAddress() == null ? "?" : p.getAddress().getHostString();
        plugin.join(p.getUniqueId(), p.getName(), ip);

        p.sendMessage("§7Впервые здесь? §f/reg <пароль> <пароль>");
        p.sendMessage("§7Уже зарегистрирован? §f/login <пароль>");
        p.sendMessage("§7На авторизацию даётся " + plugin.cfg().loginTimeout() + " сек.");
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
        // неавторизованный
        if (e.isCommand()) {
            String cmd = commandName(e.getMessage());
            if (cmd != null && ALLOWED_PRE_AUTH_COMMANDS.contains(cmd)) {
                return; // /reg /login /addtg — пропускаем к обработчику
            }
            p.sendMessage(ElytrixAuthPlugin.ERR + "Сначала авторизуйся: §f/login <пароль>§c (или §f/reg§c, если новичок).");
        } else {
            long now = ElytrixAuthPlugin.now();
            if (s == null || now - s.lastTipAt > 4) {
                if (s != null) {
                    s.lastTipAt = now;
                }
                p.sendMessage(ElytrixAuthPlugin.PREFIX + "Чат доступен после авторизации: §f/login <пароль>");
            }
        }
        e.setCancelled(true);
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
