package net.md_5.bungee.api.event;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Cancellable;
import net.md_5.bungee.api.plugin.Event;

public class ServerConnectEvent extends Event implements Cancellable {
    public enum Reason { LOBBY_FALLBACK, COMMAND, SERVER_DOWN_REDIRECT, KICK_REDIRECT, PLUGIN_MESSAGE, JOIN_PROXY, PLUGIN, UNKNOWN }
    private final ProxiedPlayer player;
    private ServerInfo target;
    private final Reason reason;
    private boolean cancelled;
    public ServerConnectEvent(ProxiedPlayer player, ServerInfo target, Reason reason) {
        this.player = player; this.target = target; this.reason = reason;
    }
    public ProxiedPlayer getPlayer() { return player; }
    public ServerInfo getTarget() { return target; }
    public void setTarget(ServerInfo target) { this.target = target; }
    public Reason getReason() { return reason; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
