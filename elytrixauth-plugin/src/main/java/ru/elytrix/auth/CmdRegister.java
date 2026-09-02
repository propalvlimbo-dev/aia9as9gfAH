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
        if (args.length != 2) {
            p.sendMessage("§eИспользование: §f/reg <пароль> <пароль>");
            return;
        }
        if (!args[0].equals(args[1])) {
            p.sendMessage(ElytrixAuthPlugin.ERR + "Пароли не совпадают.");
            return;
        }
        if (args[0].length() < plugin.cfg().minPassword()) {
            p.sendMessage(ElytrixAuthPlugin.ERR + "Пароль слишком короткий (минимум "
                    + plugin.cfg().minPassword() + " символов).");
            return;
        }
        if (args[0].length() > 64) {
            p.sendMessage(ElytrixAuthPlugin.ERR + "Пароль слишком длинный (максимум 64).");
            return;
        }
        if (plugin.db().findPlayer(s.nickname).isPresent()) {
            p.sendMessage(ElytrixAuthPlugin.ERR + "Аккаунт уже зарегистрирован. Используй §f/login <пароль>§c.");
            return;
        }

        try {
            plugin.db().createPlayer(s.uuid, s.nickname,
                    PasswordHash.create(args[0]), s.ip, ElytrixAuthPlugin.now());
        } catch (SQLException e) {
            p.sendMessage(ElytrixAuthPlugin.ERR + "Ошибка базы данных, попробуй ещё раз.");
            plugin.getLogger().severe("register error: " + e.getMessage());
            return;
        }

        plugin.markAuthed(s);
        p.sendMessage("§aРегистрация успешна! Добро пожаловать на сервер, §f" + s.nickname + "§a.");
        p.sendMessage("§7Совет: привяжи Telegram командой §f/addtg§7 — будет вход в 2 клика.");
        plugin.connectTarget(p);
    }
}
