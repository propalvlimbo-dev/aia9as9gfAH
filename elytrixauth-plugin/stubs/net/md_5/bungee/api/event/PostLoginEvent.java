package net.md_5.bungee.api.event;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Event;

public class PostLoginEvent extends Event {
    private final ProxiedPlayer player;
    public PostLoginEvent(ProxiedPlayer player) { this.player = player; }
    public ProxiedPlayer getPlayer() { return player; }
}
