package net.md_5.bungee.api.chat;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;

/**
 * Стаб: базовый компонент чата BungeeCord (рантайм-класс подставляется прокси).
 * Полный API огромен, здесь — только то, что использует ElytrixAuth.
 */
public abstract class BaseComponent {

    public BaseComponent() {
    }

    public BaseComponent(BaseComponent old) {
    }

    public void setColor(ChatColor color) {
    }

    public void setBold(Boolean bold) {
    }

    public void setItalic(Boolean italic) {
    }

    public void setUnderlined(Boolean underlined) {
    }

    public void setStrikethrough(Boolean strikethrough) {
    }

    public void setObfuscated(Boolean obfuscated) {
    }

    public void setClickEvent(ClickEvent clickEvent) {
    }

    public void setHoverEvent(HoverEvent hoverEvent) {
    }

    public void addExtra(String text) {
    }

    public void addExtra(BaseComponent component) {
    }
}
