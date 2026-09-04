package net.md_5.bungee.api.event;

import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.plugin.Event;

/**
 * Стаб Bungee API: сообщение plugin messaging (сервер -> прокси и т.п.).
 * Реальный класс есть на прокси; стаб нужен только для компиляции без Bungee.
 */
public class PluginMessageEvent extends Event {
    private final Connection sender;
    private final Connection receiver;
    private final String tag;
    private final byte[] data;

    public PluginMessageEvent(Connection sender, Connection receiver, String tag, byte[] data) {
        this.sender = sender;
        this.receiver = receiver;
        this.tag = tag;
        this.data = data;
    }

    public Connection getSender() { return sender; }
    public Connection getReceiver() { return receiver; }
    public String getTag() { return tag; }
    public byte[] getData() { return data; }
}
