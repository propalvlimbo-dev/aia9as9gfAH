package net.md_5.bungee.api.event;

import net.md_5.bungee.api.chat.BaseComponent;

/**
 * Стаб HoverEvent (BungeeCord). Рантайм-класс подставляется прокси.
 * Конструктор HoverEvent(Action, BaseComponent[]) есть в Bungee/Waterfall-ветках.
 */
public class HoverEvent {

    public enum Action {
        SHOW_TEXT,
        SHOW_ACHIEVEMENT,
        SHOW_ITEM,
        SHOW_ENTITY
    }

    private final Action action;
    private final BaseComponent[] value;

    public HoverEvent(Action action, BaseComponent[] value) {
        this.action = action;
        this.value = value;
    }

    public Action getAction() {
        return action;
    }

    public BaseComponent[] getValue() {
        return value;
    }
}
