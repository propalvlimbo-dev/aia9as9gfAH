package ru.elytrix.auth;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.sql.SQLException;

/** /addtg — начать привязку Telegram: игрок получает код и шлёт его боту /link <код>. */
public final class CmdAddTg extends Command {

    private final ElytrixAuthPlugin plugin;

    public CmdAddTg(ElytrixAuthPlugin plugin) {
        super("addtg");
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
        if (!s.isAuthed()) {
            p.sendMessage(ElytrixAuthPlugin.ERR + "Сначала авторизуйся: §f/login <пароль>");
            return;
        }

        try {
            int linkId = plugin.db().createPendingLink(
                    s.uuid, plugin.cfg().linkTtl(), ElytrixAuthPlugin.now());
            s.linkId = linkId;
            // код нужен боту: вернём его из БД по id
            String code = fetchCode(linkId);
            if (code == null) {
                p.sendMessage(ElytrixAuthPlugin.ERR + "Ошибка генерации кода, попробуй ещё раз.");
                return;
            }
            s.linkCode = code;
            p.sendMessage("§eПривязка Telegram:");
            p.sendMessage("§71) Напиши боту сервера в Telegram.");
            p.sendMessage("§72) Отправь ему: §f/link " + code);
            p.sendMessage("§7Код действует " + plugin.cfg().linkTtl() + " сек.");
        } catch (SQLException e) {
            p.sendMessage(ElytrixAuthPlugin.ERR + "Ошибка базы данных, попробуй ещё раз.");
            plugin.getLogger().severe("createPendingLink error: " + e.getMessage());
        }
    }

    private String fetchCode(int linkId) {
        try {
            return plugin.db().linkCode(linkId);
        } catch (SQLException e) {
            plugin.getLogger().severe("linkCode error: " + e.getMessage());
            return null;
        }
    }
}
