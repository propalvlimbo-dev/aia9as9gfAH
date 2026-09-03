package ru.elytrix.auth;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.sql.SQLException;

/** /addtg — привязка Telegram: игрок получает код и шлёт его боту /link <код>. */
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

        long now = ElytrixAuthPlugin.now();
        try {
            // сначала закрываем просроченные коды игрока (гигиена)
            plugin.db().expireStaleLinks(s.uuid, now);
            // если код ещё жив — показываем тот же, новый не плодим
            Database.LinkInfo live = plugin.db().findOpenLink(s.uuid, now);
            if (live != null) {
                s.linkId = live.id;
                s.linkCode = live.code;
                plugin.messages().chatList(p, "addtg-msg",
                        "code", live.code, "ttl", String.valueOf(live.expires - now));
                return;
            }
            // живого кода нет — создаём новый
            long linkId = plugin.db().createPendingLink(
                    s.uuid, plugin.cfg().linkTtl(), now);
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
