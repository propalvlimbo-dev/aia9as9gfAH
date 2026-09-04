package org.bukkit.plugin.messaging;

import org.bukkit.entity.Player;

/** Стаб org.bukkit.plugin.messaging.PluginMessageListener. */
public interface PluginMessageListener {
    void onPluginMessageReceived(String channel, Player player, byte[] message);
}
