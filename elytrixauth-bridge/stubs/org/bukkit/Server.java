package org.bukkit;

import org.bukkit.plugin.messaging.Messenger;

/** Стаб org.bukkit.Server (см. elytrixauth-bridge/stubs). */
public interface Server {
    Messenger getMessenger();
}
