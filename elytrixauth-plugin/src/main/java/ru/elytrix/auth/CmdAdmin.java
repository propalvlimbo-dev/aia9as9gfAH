package ru.elytrix.auth;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.util.Locale;

/**
 * Админ-команды (ТОЛЬКО из консоли прокси — с игры недоступны):
 *   /elytrixauth reload                 — перезагрузить конфиг/сообщения/БД
 *   /elytrixauth reset <ник>            — полный сброс (пароль + Telegram), с подтверждением
 *   /elytrixauth resetpassword <ник>    — сброс только пароля, с подтверждением
 */
public final class CmdAdmin extends Command {

    private final ElytrixAuthPlugin plugin;

    public CmdAdmin(ElytrixAuthPlugin plugin) {
        super("elytrixauth", null, "ea");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (sender instanceof ProxiedPlayer) {
            plugin.messages().sendComp(sender, "admin-console-only");
            return;
        }
        // дальше — только консоль (CommandSender без ProxiedPlayer)
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
