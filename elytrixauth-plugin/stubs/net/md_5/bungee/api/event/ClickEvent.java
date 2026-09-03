package net.md_5.bungee.api.event;

/**
 * Стаб ClickEvent (BungeeCord). Рантайм-класс подставляется прокси.
 * Достаточно совпадения FQCN и используемых сигнатур.
 */
public class ClickEvent {

    public enum Action {
        RUN_COMMAND,
        SUGGEST_COMMAND,
        OPEN_URL,
        OPEN_FILE,
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
