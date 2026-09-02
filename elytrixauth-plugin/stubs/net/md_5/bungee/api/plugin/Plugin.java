package net.md_5.bungee.api.plugin;

import net.md_5.bungee.api.ProxyServer;
import java.io.File;
import java.io.InputStream;
import java.util.logging.Logger;

public class Plugin {
    public void onLoad() {}
    public void onEnable() {}
    public void onDisable() {}

    protected final ProxyServer getProxy() { return ProxyServer.getInstance(); }
    public final File getDataFolder() { return new File("plugins/" + getDescription().getName()); }
    public final PluginDescription getDescription() { return new PluginDescription(); }
    public Logger getLogger() { return getProxy().getLogger(); }
    public final InputStream getResourceAsStream(String name) { return null; }
}
