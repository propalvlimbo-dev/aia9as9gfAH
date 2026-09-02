package net.md_5.bungee.api.event;

import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.plugin.Event;

public abstract class TargetedEvent extends Event {
    private final Connection sender;
    private final Connection receiver;
    public TargetedEvent(Connection sender, Connection receiver) {
        this.sender = sender;
        this.receiver = receiver;
    }
    public Connection getSender() { return sender; }
    public Connection getReceiver() { return receiver; }
}
