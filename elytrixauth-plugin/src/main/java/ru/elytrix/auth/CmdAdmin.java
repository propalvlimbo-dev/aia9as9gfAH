package ru.elytrix.auth;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;

import java.util.Locale;

/**
 * Админ-команды:
 *   /elytrixauth reload                 — перезагрузить конфиг/сообщения/БД
 *   /elytrixauth reset <ник>            — полный сброс (пароль + Telegram), с подтверждением
 *   /elytrixauth resetpassword <ник>    — сброс только пароля, с подтверждением
 * Доступ: permission elytrixauth.admin (консоль — всегда).
 */
public final class CmdAdmin extends Command {

    private final ElytrixAuthPlugin plugin;

    public CmdAdmin(ElytrixAuthPlugin plugin) {
        super("elytrixauth", "elytrixauth.admin", "ea");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("elytrixauth.admin")) {
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
