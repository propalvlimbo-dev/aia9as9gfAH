package net.md_5.bungee.api.chat;

/**
 * Стаб ClickEvent (BungeeCord). Рантайм-класс net.md_5.bungee.api.chat.ClickEvent
 * подставляется прокси автоматически (parent-first). ВАЖНО: пакет — chat,
 * а не event (в event такого класса у прокси нет — будет NoClassDefFoundError).
 */
public class ClickEvent {

    public enum Action {
        OPEN_URL,
        OPEN_FILE,
        RUN_COMMAND,
        SUGGEST_COMMAND,
        CHANGE_PAGE,
        SHOW_DIALOG,
        COPY_TO_CLIPBOARD
    }

    private final Action action;
    private final String value;

    public ClickEvent(Action action, String value) {
        this.action = action;
        this.value = value;
    }

    public Action getAction() {
        return action;
    }

    public String getValue() {
        return value;
    }
}
