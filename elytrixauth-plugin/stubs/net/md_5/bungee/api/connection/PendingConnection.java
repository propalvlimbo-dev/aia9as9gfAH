package net.md_5.bungee.api.connection;

import java.util.UUID;

public interface PendingConnection extends Connection {
    UUID getUniqueId();
}
