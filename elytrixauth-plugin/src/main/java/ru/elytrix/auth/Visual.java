package ru.elytrix.auth;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.protocol.DefinedPacket;

import java.util.UUID;

/**
 * Экранные подсказки: title по центру экрана, actionbar над хотбаром,
 * пурпурный bossbar с таймером.
 *
 * Title/actionbar — официальный Bungee API. BossBar — отдельный пакет,
 * который отправляется через {@code Connection.unsafe().sendPacket(...)}:
 * класс пакета есть только в рантайме прокси, поэтому строится рефлексией,
 * а вот сам метод отправки вызывается напрямую через публичный интерфейс
 * (без рефлексии — иначе на некоторых форках ловится IllegalAccessException).
 */
public final class Visual {

    /** Цвет пурпурный в пакете BossBar. */
    private static final int COLOR_PURPLE = 5;
    private static boolean barWarned = false;

    private Visual() {
    }

    // ---------------------------------------------------------------- title

    /** Большой текст по центру экрана (виден даже при выключенном чате). */
    public static void title(ProxiedPlayer p, String main, String sub) {
        title(p, main, sub, 5, 100, 15);
    }

    /** Title с произвольной длительностью (в тиках: 20 тиков = 1 сек). */
    public static void title(ProxiedPlayer p, String main, String sub, int fadeIn, int stay, int fadeOut) {
        try {
            Title t = ProxyServer.getInstance().createTitle()
                    .title(Messages.comp(main))
                    .subTitle(Messages.comp(sub))
                    .fadeIn(fadeIn)
                    .stay(stay)
                    .fadeOut(fadeOut);
            t.send(p);
        } catch (Throwable ignored) {
        }
    }

    /** Убрать текущий title с экрана. */
    public static void clearTitle(ProxiedPlayer p) {
        try {
            ProxyServer.getInstance().createTitle().reset().send(p);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- actionbar

    public static void actionbar(ProxiedPlayer p, String text) {
        try {
            BaseComponent[] c = Messages.comp(text);
            if (c.length > 0) {
                p.sendMessage(ChatMessageType.ACTION_BAR, c);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- bossbar

    /**
     * Пурпурный bossbar. Хендл позволяет обновлять текст/прогресс и убирать бар.
     * Если прокси не умеет принимать пакет — возвращается null (работаем без бара,
     * время дублируется в actionbar).
     */
    public static BossBar startBossBar(ProxiedPlayer p, String initialText) {
        try {
            Class<?> cls = Class.forName("net.md_5.bungee.protocol.packet.BossBar");
            UUID id = UUID.randomUUID();

            Object bar = cls.getConstructor(UUID.class, int.class).newInstance(id, 0); // add
            cls.getMethod("setTitle", BaseComponent.class).invoke(bar, firstComp(initialText));
            cls.getMethod("setHealth", float.class).invoke(bar, 1f);
            cls.getMethod("setColor", int.class).invoke(bar, COLOR_PURPLE);
            cls.getMethod("setDivision", int.class).invoke(bar, 0);
            cls.getMethod("setFlags", byte.class).invoke(bar, (byte) 0);

            send(p, bar);
            return new BossBar(p, cls, id);
        } catch (Throwable t) {
            if (!barWarned) {
                barWarned = true;
                System.err.println("[ElytrixAuth] BossBar недоступен на этом прокси ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + ") — время показываем в actionbar.");
            }
            return null; // прокси без поддержки пакета — работаем без боссбара
        }
    }

    /** Отправка пакета напрямую через публичный интерфейс Connection.unsafe(). */
    private static void send(ProxiedPlayer p, Object barPacket) {
        p.unsafe().sendPacket((DefinedPacket) barPacket);
    }

    /** Обновление живого боссбара. health 0..1, text может быть null (не менять текст). */
    public static final class BossBar {
        private final ProxiedPlayer player;
        private final Class<?> cls;
        private final UUID id;
        private boolean alive = true;

        private BossBar(ProxiedPlayer player, Class<?> cls, UUID id) {
            this.player = player;
            this.cls = cls;
            this.id = id;
        }

        public synchronized void update(float health, String text) {
            if (!alive) {
                return;
            }
            try {
                if (health < 0) {
                    health = 0;
                }
                if (health > 1) {
                    health = 1;
                }
                Object bar = cls.getConstructor(UUID.class, int.class).newInstance(id, 2); // health
                cls.getMethod("setHealth", float.class).invoke(bar, health);
                send(player, bar);
                if (text != null) {
                    Object bar2 = cls.getConstructor(UUID.class, int.class).newInstance(id, 3); // title
                    cls.getMethod("setTitle", BaseComponent.class).invoke(bar2, firstComp(text));
                    send(player, bar2);
                }
            } catch (Throwable ignored) {
            }
        }

        /** Убрать боссбар с экрана. */
        public synchronized void remove() {
            if (!alive) {
                return;
            }
            alive = false;
            try {
                Object bar = cls.getConstructor(UUID.class, int.class).newInstance(id, 1); // remove
                send(player, bar);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static BaseComponent firstComp(String text) {
        BaseComponent[] arr = Messages.comp(text);
        if (arr.length == 0) {
            return new TextComponent("");
        }
        return arr[0];
    }
}
