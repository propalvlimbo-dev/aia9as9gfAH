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
            sender.sendMessage(plugin.messages().raw("cmd-only-player"));
            return;
        }
        ProxiedPlayer p = (ProxiedPlayer) sender;
        AuthSession s = plugin.session(p.getUniqueId());
        if (s == null) {
            return;
        }
        if (!s.isAuthed()) {
            plugin.messages().chat(p, "addtg-not-authed");
            return;
        }

        try {
            long linkId = plugin.db().createPendingLink(
                    s.uuid, plugin.cfg().linkTtl(), ElytrixAuthPlugin.now());
            String code = plugin.db().linkCode(linkId);
            if (code == null) {
                plugin.messages().chat(p, "addtg-db-error");
                return;
            }
            s.linkId = linkId;
            s.linkCode = code;
            plugin.messages().chatList(p, "addtg-msg",
                    "code", code, "ttl", String.valueOf(plugin.cfg().linkTtl()));
        } catch (SQLException e) {
            plugin.messages().chat(p, "db-error");
            plugin.getLogger().severe("createPendingLink error: " + e.getMessage());
        }
    }
}
