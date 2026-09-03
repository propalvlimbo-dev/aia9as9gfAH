package net.md_5.bungee.api.event;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Event;

/** Стаб ServerConnectedEvent: игрок подключился к серверу сети. */
public class ServerConnectedEvent extends Event {

    private final ProxiedPlayer player;
    private final ServerInfo server;

    public ServerConnectedEvent(ProxiedPlayer player, ServerInfo server) {
        this.player = player;
        this.server = server;
    }

    public ProxiedPlayer getPlayer() {
        return player;
    }

    public ServerInfo getServer() {
        return server;
    }
}
