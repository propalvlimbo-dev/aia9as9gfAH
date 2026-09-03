package net.md_5.bungee.api.event;

import java.util.List;

import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.plugin.Cancellable;

/** Стаб TabCompleteEvent (BungeeCord): срабатывает при нажатии Tab в чате/команде. */
public class TabCompleteEvent extends TargetedEvent implements Cancellable {

    private boolean cancelled;
    private final String cursor;
    private final List<String> suggestions;

    public TabCompleteEvent(Connection sender, Connection receiver, String cursor, List<String> suggestions) {
        super(sender, receiver);
        this.cursor = cursor;
        this.suggestions = suggestions;
    }

    /** Что игрок уже ввёл (например "/lo"). */
    public String getCursor() {
        return cursor;
    }

    /** Изменяемый список подсказок. Если оставить пустым — запрос уйдёт на сервер. */
    public List<String> getSuggestions() {
        return suggestions;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
