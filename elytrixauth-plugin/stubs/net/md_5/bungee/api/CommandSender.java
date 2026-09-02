package net.md_5.bungee.api;

public interface CommandSender {
    String getName();
    void sendMessage(String message);
    boolean hasPermission(String permission);
}
