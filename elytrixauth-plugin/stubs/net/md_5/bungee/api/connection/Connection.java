package net.md_5.bungee.api.connection;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public interface Connection {
    InetSocketAddress getAddress();
    SocketAddress getSocketAddress();
    void disconnect(String reason);
}
