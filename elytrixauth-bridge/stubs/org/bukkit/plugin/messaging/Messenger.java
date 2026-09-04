package org.bukkit.plugin.messaging;

import org.bukkit.plugin.Plugin;

/** Стаб org.bukkit.plugin.messaging.Messenger. */
public interface Messenger {
    void registerOutgoingPluginChannel(Plugin plugin, String channel);
    void registerIncomingPluginChannel(Plugin plugin, String channel, PluginMessageListener listener);
}
