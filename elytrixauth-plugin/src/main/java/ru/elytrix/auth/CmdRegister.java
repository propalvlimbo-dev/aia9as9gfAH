package ru.elytrix.auth;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.sql.SQLException;

/** /reg <пароль> <пароль>  (алиас /register) */
public final class CmdRegister extends Command {

    private final ElytrixAuthPlugin plugin;

    public CmdRegister(ElytrixAuthPlugin plugin) {
        super("register", null, "reg");
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
        if (s.isAuthed()) {
            plugin.messages().chat(p, "already-authed");
            return;
        }
        if (args.length != 2) {
            plugin.messages().chat(p, "usage-reg");
            return;
        }
        if (!args[0].equals(args[1])) {
            plugin.messages().chat(p, "pass-mismatch");
            return;
        }
        if (args[0].length() < plugin.cfg().minPassword()) {
            plugin.messages().chat(p, "pass-short", "min", String.valueOf(plugin.cfg().minPassword()));
            return;
        }
        if (args[0].length() > 64) {
            plugin.messages().chat(p, "pass-long", "max", "64");
            return;
        }
        if (plugin.db().findPlayer(s.nickname).isPresent()) {
            plugin.messages().chat(p, "account-exists");
            return;
        }

        try {
            plugin.db().createPlayer(s.uuid, s.nickname,
                    PasswordHash.create(args[0]), s.ip, ElytrixAuthPlugin.now());
        } catch (SQLException e) {
            plugin.messages().chat(p, "db-error");
            plugin.getLogger().severe("register error: " + e.getMessage());
            return;
        }

        // сразу выдаём сессию — при перезаходе с того же IP пароль не спросим
        if (plugin.cfg().sessionsEnabled()) {
            try {
                plugin.db().updateSession(s.uuid, s.ip,
                        ElytrixAuthPlugin.now() + plugin.cfg().sessionMaxSeconds());
            } catch (SQLException e) {
                plugin.getLogger().severe("updateSession(reg) error: " + e.getMessage());
            }
        }

        plugin.markAuthed(s);
        plugin.messages().chatList(p, "register-ok", "player", s.nickname);
        plugin.connectTarget(p);
    }
}
