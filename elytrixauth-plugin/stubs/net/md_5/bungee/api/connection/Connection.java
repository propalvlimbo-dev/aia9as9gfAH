package net.md_5.bungee.api.connection;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.md_5.bungee.api.chat.BaseComponent;

public interface Connection {
    InetSocketAddress getAddress();
    SocketAddress getSocketAddress();
    void disconnect(String reason);
    void disconnect(BaseComponent... reason);
    boolean isConnected();
}
