package org.bukkit.plugin.java;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/** Стаб org.bukkit.plugin.java.JavaPlugin (см. Plugin). */
public class JavaPlugin implements Plugin {
    public Server getServer() { return null; }
    public Logger getLogger() { return null; }
    public void onEnable() {}
    public void onDisable() {}
}
