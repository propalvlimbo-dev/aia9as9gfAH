package ru.elytrix.auth;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.sql.SQLException;

/** /logout — выйти из аккаунта: сессия и авторизация снимаются, нужен повторный /login. */
public final class CmdLogout extends Command {

    private final ElytrixAuthPlugin plugin;

    public CmdLogout(ElytrixAuthPlugin plugin) {
        super("logout");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) {
            sender.sendMessage(plugin.messages().raw("cmd-only-player"));
            return;
        }
        ProxiedPlayer p = (ProxiedPlayer) sender;
        AuthSession s = plugin.session(p.getUniqueId());
        if (s == null) {
            return;
        }
        if (!s.isAuthed()) {
            plugin.messages().chat(p, "logout-not-authed");
            return;
        }
        // снимаем сессию в БД (авто-вход больше не сработает) и память
        try {
            plugin.db().clearSession(s.uuid);
        } catch (SQLException e) {
            plugin.getLogger().severe("clearSession(logout) error: " + e.getMessage());
        }
        plugin.clearFails("nick:" + s.nickname.toLowerCase());
        plugin.clearFails("ip:" + s.ip);
        plugin.leave(p.getUniqueId());
        plugin.messages().kick(p, "kick-logged-out");
    }
}
