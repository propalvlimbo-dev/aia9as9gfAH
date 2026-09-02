package net.md_5.bungee.api.connection;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.config.ServerInfo;
import java.util.UUID;

public interface ProxiedPlayer extends Connection, CommandSender {
    UUID getUniqueId();
    String getName();
    Server getServer();
    PendingConnection getPendingConnection();
    void connect(ServerInfo target);
}
