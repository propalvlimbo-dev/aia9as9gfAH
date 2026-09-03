package net.md_5.bungee.api;

import net.md_5.bungee.api.chat.BaseComponent;

public interface CommandSender {
    String getName();
    void sendMessage(String message);
    void sendMessage(BaseComponent... message);
    boolean hasPermission(String permission);
}
