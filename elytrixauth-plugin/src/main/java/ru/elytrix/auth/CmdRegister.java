package ru.elytrix.auth;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.sql.SQLException;

/** /reg <пароль> <пароль>  (алиас /register) */public final class CmdRegister extends Command {

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
        // простые пароли не принимаем
        if (args[0].equalsIgnoreCase(s.nickname)) {
            plugin.messages().chat(p, "pass-like-nick");
            return;
        }
        if (args[0].length() > 1 && allSameChars(args[0])) {
            plugin.messages().chat(p, "pass-same-chars");
            return;
        }
        // аккаунт уже есть — но без пароля (сброс админом) — тогда просто задаём новый
        Database.PlayerRow existing = plugin.db().findPlayer(s.nickname).orElse(null);
        if (existing != null && existing.passwordHash != null) {
            plugin.messages().chat(p, "account-exists");
            return;
        }
        // лимит регистраций с одного IP (новый аккаунт; смена пароля не считается)
        if (existing == null && plugin.cfg().regMaxPerIp() > 0) {
            long regs = plugin.db().countPlayersByRegIp(s.ip);
            if (regs >= plugin.cfg().regMaxPerIp()) {
                plugin.messages().chat(p, "reg-ip-limit",
                        "max", String.valueOf(plugin.cfg().regMaxPerIp()));
                return;
            }
        }

        try {
            String hash = PasswordHash.create(args[0]);
            if (existing != null) {
                plugin.db().setPassword(existing.uuid, hash);
            } else {
                plugin.db().createPlayer(s.uuid, s.nickname,
                        hash, s.ip, ElytrixAuthPlugin.now());
            }
        } catch (SQLException e) {
            plugin.messages().chat(p, "db-error");
            plugin.getLogger().severe("register error: " + e.getMessage());
            return;
        }

        // первая запись в истории входов (регистрация = вход)
        try {
            plugin.db().recordLogin(s.uuid, s.ip, ElytrixAuthPlugin.now());
        } catch (SQLException e) {
            plugin.getLogger().warning("recordLogin(reg) error: " + e.getMessage());
        }

        // Регистрация завершена — авторизуем сразу, но перевод на игровой сервер
        // делаем ЧЕРЕЗ КОРОТКУЮ ЗАДЕРЖКУ, а не в момент команды: некоторые клиенты
        // (и агрессивные прокси/VPN-цепочки) не успевают дочитать входящий поток
        // и при быстром connect+kick ловят «ошибка сетевого протокола» в момент
        // переключения. Титул успеха/приветствие при этом успевает показаться,
        // авторизация уже стоит — игрок остаётся на auth пару секунд и затем
        // уходит на target как обычно.
        // Сразу выдаём сессию — при перезаходе с того же IP пароль не спросим.
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
        plugin.playCheckAnimation(p, "check-done-reg", true);
    }

    /** Пароль из одинаковых символов («aaaaaa», «111111») — слишком простой. */
    private static boolean allSameChars(String v) {
        char c = v.charAt(0);
        for (int i = 1; i < v.length(); i++) {
            if (v.charAt(i) != c) {
                return false;
            }
        }
        return true;
    }
}
