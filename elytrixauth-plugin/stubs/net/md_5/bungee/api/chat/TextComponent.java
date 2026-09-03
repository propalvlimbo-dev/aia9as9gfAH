package net.md_5.bungee.api.chat;

/**
 * Стаб TextComponent (BungeeCord). Рантайм-класс net.md_5.bungee.api.chat.TextComponent
 * подставляется прокси автоматически (parent-first), поэтому достаточно совпадения FQCN
 * и сигнатур используемых методов.
 */
public class TextComponent extends BaseComponent {

    public TextComponent() {
        super();
    }

    public TextComponent(String text) {
        super();
    }

    public TextComponent(BaseComponent... components) {
        super();
    }

    /** Разбор legacy-строки с &-кодами (включая &x&R&R&G&G&B&B для hex) в компоненты. */
    public static BaseComponent[] fromLegacyText(String message) {
        return new BaseComponent[]{new TextComponent(message)};
    }
}
