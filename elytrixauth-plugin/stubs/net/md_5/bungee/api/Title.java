package net.md_5.bungee.api;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/** Стаб Title (BungeeCord): конфигурация титула (большой текст по центру экрана). */
public interface Title {

    Title title(BaseComponent text);

    Title title(BaseComponent... text);

    Title subTitle(BaseComponent text);

    Title subTitle(BaseComponent... text);

    Title fadeIn(int ticks);

    Title stay(int ticks);

    Title fadeOut(int ticks);

    Title clear();

    Title reset();

    Title send(ProxiedPlayer player);
}
