package net.md_5.bungee.api.plugin;

import net.md_5.bungee.api.CommandSender;

public abstract class Command {
    private final String name;
    private final String permission;
    private final String[] aliases;

    public Command(String name) { this(name, null); }
    public Command(String name, String permission, String... aliases) {
        this.name = name; this.permission = permission; this.aliases = aliases;
    }
    public abstract void execute(CommandSender sender, String[] args);
    public String getName() { return name; }
    public String getPermission() { return permission; }
    public String[] getAliases() { return aliases; }
    public boolean hasPermission(CommandSender sender) {
        return permission == null || permission.isEmpty() || sender.hasPermission(permission);
    }
}
