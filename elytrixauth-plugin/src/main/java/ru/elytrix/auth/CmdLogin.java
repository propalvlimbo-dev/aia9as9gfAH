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
            sender.sendMessage("Команда доступна только игрокам.");
            return;
        }
        ProxiedPlayer p = (ProxiedPlayer) sender;
        AuthSession s = plugin.session(p.getUniqueId());
        if (s == null) {
            return;
        }
        if (s.isAuthed()) {
            p.sendMessage(ElytrixAuthPlugin.ERR + "Ты уже авторизован.");
            return;
        }
        if (s.state == AuthSession.State.TG) {
            p.sendMessage(ElytrixAuthPlugin.ERR + "Пароль уже принят. Подтверди вход в Telegram.");
            return;
        }
        if (args.length != 1) {
            p.sendMessage("§eИспользование: §f/login <пароль>");
            return;
        }

        // защита от перебора: по нику и по IP
        if (plugin.isFailBlocked("nick:" + s.nickname.toLowerCase()) || plugin.isFailBlocked("ip:" + s.ip)) {
            p.disconnect("§cСлишком много неудачных попыток входа. Подожди немного и зайди снова.");
            return;
        }

        Database.PlayerRow row = plugin.db().findPlayer(s.nickname).orElse(null);
        if (row == null) {
            p.sendMessage(ElytrixAuthPlugin.ERR + "Аккаунт не найден. Зарегистрируйся: §f/reg <пароль> <пароль>");
            return;
        }
        if (row.passwordHash == null || !PasswordHash.verify(args[0], row.passwordHash)) {
            plugin.registerFail("nick:" + s.nickname.toLowerCase());
            plugin.registerFail("ip:" + s.ip);
            p.sendMessage(ElytrixAuthPlugin.ERR + "Неверный пароль.");
            return;
        }
        plugin.clearFails("nick:" + s.nickname.toLowerCase());
        plugin.clearFails("ip:" + s.ip);

        try {
            plugin.db().updateLastLogin(s.uuid, s.ip, ElytrixAuthPlugin.now());
        } catch (SQLException e) {
            plugin.getLogger().severe("updateLastLogin error: " + e.getMessage());
        }

        if (row.tgId == null) {
            // нет привязки — впускаем сразу
            plugin.markAuthed(s);
            p.sendMessage("§aВход выполнен. Добро пожаловать, §f" + s.nickname + "§a!");
            plugin.connectTarget(p);
        } else {
            // 2FA: пароль верный — ждём кнопку в Telegram
            try {
                long reqId = plugin.db().createLoginRequest(
                        s.uuid, s.nickname, s.ip, plugin.cfg().login2faTtl(), ElytrixAuthPlugin.now());
                s.requestId = reqId;
                s.state = AuthSession.State.TG;
                s.deadline = ElytrixAuthPlugin.now() + plugin.cfg().login2faTtl();
                p.sendMessage("§eПароль верный. Подтверди вход кнопкой в Telegram (в течение "
                        + plugin.cfg().login2faTtl() + " сек).");
            } catch (SQLException e) {
                p.sendMessage(ElytrixAuthPlugin.ERR + "Ошибка базы данных, попробуй ещё раз.");
                plugin.getLogger().severe("createLoginRequest error: " + e.getMessage());
            }
        }
    }
}
