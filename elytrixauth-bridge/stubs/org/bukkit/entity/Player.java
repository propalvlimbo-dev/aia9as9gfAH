package org.bukkit.entity;

import org.bukkit.plugin.Plugin;

/** Стаб org.bukkit.entity.Player. */
public interface Player {
    void sendPluginMessage(Plugin source, String channel, byte[] message);
    int getStatistic(org.bukkit.Statistic statistic);
    java.util.UUID getUniqueId();
}
