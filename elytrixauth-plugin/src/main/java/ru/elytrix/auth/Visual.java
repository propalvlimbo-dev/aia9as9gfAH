package ru.elytrix.auth;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.UUID;

/**
 * Экранные подсказки: title по центру экрана, actionbar над хотбаром,
 * пурпурный bossbar с таймером.
 *
 * Title/actionbar — официальный Bungee API. BossBar отправляется клиенту
 * отдельным пакетом через {@code Connection.unsafe().sendPacket(...)}:
 * в API-стабах этих классов нет, поэтому всё делается рефлексией, и если
 * прокси не поддерживает пакет — плагин просто работает без боссбара.
 */
public final class Visual {

    /** Цвет пурпурный в пакете BossBar. */
    private static final int COLOR_PURPLE = 5;

    private Visual() {
    }

    // ---------------------------------------------------------------- title

    /** Большой текст по центру экрана (виден даже при выключенном чате). */
    public static void title(ProxiedPlayer p, String main, String sub) {
        try {
            Title t = ProxyServer.getInstance().createTitle()
                    .title(Messages.comp(main))
                    .subTitle(Messages.comp(sub))
                    .fadeIn(10)
                    .stay(80)
                    .fadeOut(20);
            t.send(p);
        } catch (Throwable ignored) {
            // если Title вдруг не поддержан — не роняем ничего
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
     * Если прокси не умеет слать пакет — возвращается null (работаем без бара).
     */
    public static BossBar startBossBar(ProxiedPlayer p, String initialText) {
        try {
            Class<?> cls = Class.forName("net.md_5.bungee.protocol.packet.BossBar");
            Class<?> defined = Class.forName("net.md_5.bungee.protocol.DefinedPacket");
            UUID id = UUID.randomUUID();

            Object bar = cls.getConstructor(UUID.class, int.class).newInstance(id, 0); // add
            BaseComponent first = firstComp(initialText);
            cls.getMethod("setTitle", BaseComponent.class).invoke(bar, first);
            cls.getMethod("setHealth", float.class).invoke(bar, 1f);
            cls.getMethod("setColor", int.class).invoke(bar, COLOR_PURPLE);
            cls.getMethod("setDivision", int.class).invoke(bar, 0);
            cls.getMethod("setFlags", byte.class).invoke(bar, (byte) 0);

            Object unsafe = findUnsafe(p);
            Object send = unsafe.getClass().getMethod("sendPacket", defined).invoke(unsafe, bar);
            if (send != null) {
                // не должно быть null, но на всякий случай
            }
            return new BossBar(cls, unsafe, id);
        } catch (Throwable t) {
            return null; // прокси без поддержки пакета — работаем без боссбара
        }
    }

    /** Обновление живого боссбара. health 0..1, text может быть null (не менять текст). */
    public static final class BossBar {
        private final Class<?> cls;
        private final Object unsafe;
        private final UUID id;
        private boolean alive = true;

        private BossBar(Class<?> cls, Object unsafe, UUID id) {
            this.cls = cls;
            this.unsafe = unsafe;
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
                send(bar);
                if (text != null) {
                    Object bar2 = cls.getConstructor(UUID.class, int.class).newInstance(id, 3); // title
                    cls.getMethod("setTitle", BaseComponent.class).invoke(bar2, firstComp(text));
                    send(bar2);
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
                send(bar);
            } catch (Throwable ignored) {
            }
        }

        private void send(Object bar) throws Exception {
            Class<?> defined = Class.forName("net.md_5.bungee.protocol.DefinedPacket");
            unsafe.getClass().getMethod("sendPacket", defined).invoke(unsafe, bar);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Object findUnsafe(Object player) throws Exception {
        // у ProxiedPlayer (реализация в прокси) есть метод unsafe(), объявленный
        // в интерфейсе net.md_5.bungee.api.connection.Connection
        java.lang.reflect.Method m = player.getClass().getMethod("unsafe");
        return m.invoke(player);
    }

    private static BaseComponent firstComp(String text) {
        BaseComponent[] arr = Messages.comp(text);
        if (arr.length == 0) {
            return new TextComponent("");
        }
        return arr[0];
    }
}
