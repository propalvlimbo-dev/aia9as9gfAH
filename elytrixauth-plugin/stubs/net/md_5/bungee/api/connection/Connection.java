package net.md_5.bungee.api.connection;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.protocol.DefinedPacket;

public interface Connection {
    InetSocketAddress getAddress();
    SocketAddress getSocketAddress();
    void disconnect(String reason);
    void disconnect(BaseComponent... reason);
    boolean isConnected();
    Unsafe unsafe();

    /** Низкоуровневый доступ: отправка «сырых» пакетов игроку. */
    interface Unsafe {
        void sendPacket(DefinedPacket packet);
        void sendPacketQueued(DefinedPacket packet);
    }
}
