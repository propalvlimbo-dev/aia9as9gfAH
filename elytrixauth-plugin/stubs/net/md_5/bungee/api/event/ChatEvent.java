package net.md_5.bungee.api.event;

import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.plugin.Cancellable;

public class ChatEvent extends TargetedEvent implements Cancellable {
    private String message;
    private boolean cancelled;
    public ChatEvent(Connection sender, Connection receiver, String message) {
        super(sender, receiver);
        this.message = message;
    }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public boolean isCommand() { return message != null && message.startsWith("/"); }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
