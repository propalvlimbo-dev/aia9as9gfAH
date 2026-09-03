package ru.elytrix.auth;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.util.Collection;
import java.util.Locale;

/**
 * Админ-команды:
 *   /elytrixauth reload                 — перезагрузить конфиг/сообщения/БД
 *   /elytrixauth reset <ник>            — полный сброс (пароль + Telegram), с подтверждением
 *   /elytrixauth resetpassword <ник>    — сброс только пароля, с подтверждением
 * Доступ: право elytrixauth.admin, либо "*" / "elytrixauth.*" (консоль — всегда).
 */
public final class CmdAdmin extends Command {

    private final ElytrixAuthPlugin plugin;

    public CmdAdmin(ElytrixAuthPlugin plugin) {
        super("elytrixauth", null, "ea");
        this.plugin = plugin;
    }

    /** Доступ для игроков с elytrixauth.admin, "*" или elytrixauth.*; консоль — всегда. */
    private static boolean isAdmin(CommandSender sender) {
        if (!(sender instanceof ProxiedPlayer)) {
            return true; // консоль и прочие не-игроки
        }
        ProxiedPlayer p = (ProxiedPlayer) sender;
        if (p.hasPermission("elytrixauth.admin")
                || p.hasPermission("elytrixauth.*")
                || p.hasPermission("*")) {
            return true;
        }
        // на некоторых прокси (Bungee/форки) hasPermission не трактует "*" как wildcard —
        // проверяем сам список прав
        Collection<String> perms = p.getPermissions();
        return perms != null && (perms.contains("elytrixauth.admin")
                || perms.contains("elytrixauth.*")
                || perms.contains("*"));
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) {
            plugin.messages().sendComp(sender, "admin-no-perm");
            return;
        }
        if (args.length == 0) {
            plugin.messages().sendCompList(sender, "admin-usage");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload":
            case "rl":
                plugin.runAsync(() -> {
                    plugin.reloadPlugin();
                    plugin.messages().sendComp(sender, "admin-reloaded");
                });
                break;
            case "reset":
                if (args.length < 2) {
                    plugin.messages().sendComp(sender, "admin-usage-reset");
                    return;
                }
                final String nickFull = args[1];
                plugin.runAsync(() -> plugin.handleAdminReset(sender, nickFull, false));
                break;
            case "resetpassword":
            case "resetpass":
                if (args.length < 2) {
                    plugin.messages().sendComp(sender, "admin-usage-resetpassword");
                    return;
                }
                final String nickPass = args[1];
                plugin.runAsync(() -> plugin.handleAdminReset(sender, nickPass, true));
                break;
            default:
                plugin.messages().sendCompList(sender, "admin-usage");
        }
    }
}
