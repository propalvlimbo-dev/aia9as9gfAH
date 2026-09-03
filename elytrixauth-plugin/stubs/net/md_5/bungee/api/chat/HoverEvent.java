package net.md_5.bungee.api.chat;

/**
 * Стаб HoverEvent (BungeeCord). Рантайм-класс net.md_5.bungee.api.chat.HoverEvent
 * подставляется прокси автоматически (parent-first). ВАЖНО: пакет — chat.
 */
public class HoverEvent {

    public enum Action {
        SHOW_TEXT,
        SHOW_ITEM,
        SHOW_ENTITY,
        SHOW_ACHIEVEMENT
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
