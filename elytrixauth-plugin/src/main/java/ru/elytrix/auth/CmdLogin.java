package ru.elytrix.auth;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.sql.SQLException;

/** /login <пароль>  (алиас /l) */
public final class CmdLogin extends Command {

    private final ElytrixAuthPlugin plugin;

    public CmdLogin(ElytrixAuthPlugin plugin) {
        super("login", null, "l");
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
        if (s.state == AuthSession.State.TG) {
            plugin.messages().chat(p, "tg-already");
            return;
        }
        if (args.length != 1) {
            plugin.messages().chat(p, "usage-login");
            return;
        }

        // защита от перебора: по нику и по IP
        if (plugin.isFailBlocked("nick:" + s.nickname.toLowerCase()) || plugin.isFailBlocked("ip:" + s.ip)) {
            plugin.messages().kick(p, "kick-too-many-tries");
            return;
        }

        Database.PlayerRow row = plugin.db().findPlayer(s.nickname).orElse(null);
        if (row == null) {
            plugin.messages().chat(p, "not-registered");
            return;
        }
        if (row.passwordHash == null) {
            plugin.messages().chat(p, "account-no-password");
            return;
        }
        if (!PasswordHash.verify(args[0], row.passwordHash)) {
            plugin.registerFail("nick:" + s.nickname.toLowerCase());
            plugin.registerFail("ip:" + s.ip);
            // временный бан IP после N неудачных входов (по умолчанию 3)
            if (plugin.cfg().ipBanEnabled()
                    && plugin.failCount("ip:" + s.ip) >= plugin.cfg().banIpAfterTries()) {
                plugin.banIp(s.ip);
                plugin.messages().kick(p, "kick-ip-banned",
                        "time", String.valueOf(Math.max(1, plugin.cfg().banIpMinutes())));
                return;
            }
            if (plugin.isFailBlocked("nick:" + s.nickname.toLowerCase())
                    || plugin.isFailBlocked("ip:" + s.ip)) {
                plugin.messages().kick(p, "kick-too-many-tries");
                return;
            }
            plugin.messages().chat(p, "wrong-password",
                    "left", String.valueOf(plugin.failLeft("ip:" + s.ip)));
            return;
        }
        plugin.clearFails("nick:" + s.nickname.toLowerCase());
        plugin.clearFails("ip:" + s.ip);

        if (row.tgId == null) {
            // нет привязки — впускаем сразу и выдаём сессию
            if (plugin.cfg().sessionsEnabled()) {
                try {
                    plugin.db().updateSession(row.uuid, s.ip,
                            ElytrixAuthPlugin.now() + plugin.cfg().sessionMaxSeconds());
                } catch (SQLException e) {
                    plugin.getLogger().severe("updateSession(login) error: " + e.getMessage());
                }
            }
            plugin.markAuthed(s);
            plugin.messages().chat(p, "login-ok", "player", s.nickname);
            plugin.connectTarget(p);
        } else {
            // 2FA: пароль верный — ждём кнопку в Telegram
            try {
                long reqId = plugin.db().createLoginRequest(
                        s.uuid, s.nickname, s.ip, plugin.cfg().login2faTtl(), ElytrixAuthPlugin.now());
                s.requestId = reqId;
                s.state = AuthSession.State.TG;
                s.deadline = ElytrixAuthPlugin.now() + plugin.cfg().login2faTtl();
                s.totalSec = plugin.cfg().login2faTtl();
                plugin.messages().chatList(p, "tg-wait-confirm", "ttl", String.valueOf(plugin.cfg().login2faTtl()));
                // переключаем боссбар/подсказки на ожидание Telegram
                if (s.bar != null) {
                    s.bar.remove();
                    s.bar = null;
                }
                s.barText = null;
                Visual.BossBar bar = Visual.startBossBar(p,
                        plugin.messages().raw("bossbar-tg",
                                "sec", String.valueOf(plugin.cfg().login2faTtl())));
                if (bar != null) {
                    s.bar = bar;
                    s.bar.update(1f, null);
                }
            } catch (SQLException e) {
                plugin.messages().chat(p, "db-error");
                plugin.getLogger().severe("createLoginRequest error: " + e.getMessage());
            }
        }
    }
}
