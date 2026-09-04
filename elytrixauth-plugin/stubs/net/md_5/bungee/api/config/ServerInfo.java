package net.md_5.bungee.api.config;

import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.net.InetSocketAddress;
import java.util.Collection;

public interface ServerInfo {
    String getName();
    InetSocketAddress getAddress();
    Collection<ProxiedPlayer> getPlayers();
}
