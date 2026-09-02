package net.md_5.bungee.api;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.PluginManager;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public abstract class ProxyServer {
    private static ProxyServer instance;
    public static ProxyServer getInstance() { return instance; }
    public static void setInstance(ProxyServer i) { instance = i; }
    public abstract String getName();
    public abstract Logger getLogger();
    public abstract Collection<ProxiedPlayer> getPlayers();
    public abstract ProxiedPlayer getPlayer(String name);
    public abstract ProxiedPlayer getPlayer(UUID uuid);
    public abstract Map<String, ServerInfo> getServers();
    public abstract ServerInfo getServerInfo(String name);
    public abstract PluginManager getPluginManager();
}
