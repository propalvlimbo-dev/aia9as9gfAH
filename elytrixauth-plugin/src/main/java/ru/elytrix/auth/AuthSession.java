package ru.elytrix.auth;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Сессия игрока на прокси. */
public final class AuthSession {

    public enum State {
        WAIT,   // нужно /reg или /login
        TG,     // пароль верный, ждём подтверждение в Telegram (2FA)
        OK      // авторизован
    }

    public final UUID uuid;
    public final String nickname;
    public final String ip;

    public volatile State state = State.WAIT;
    /** epoch-сек: дедлайн авторизации (0 = нет лимита). */
    public volatile long deadline = 0;
    /** сколько всего секунд дано на авторизацию (для прогресса). */
    public volatile int totalSec = 0;
    /** true — аккаунта нет в базе, нужно /reg (иначе /login). */
    public volatile boolean needReg = false;
    /** показать игроку, что сессия была сброшена (сменился IP). */
    public volatile boolean sessionDropped = false;
    /** id строки login_requests для 2FA (-1 = нет). */
    public volatile long requestId = -1;
    /** id строки pending_links для /addtg (-1 = нет). */
    public volatile long linkId = -1;
    /** последний сгенерированный код привязки. */
    public volatile String linkCode = null;
    /** живой bossbar (null, если не поддерживается прокси). */
    public volatile Visual.BossBar bar = null;
    /** последний текст боссбара, чтобы не спамить пакеты. */
    public volatile String barText = null;

    /** для анти-спама подсказок в чате. */
    public volatile long lastTipAt = 0;

    private static final AtomicInteger SEQ = new AtomicInteger();

    public AuthSession(UUID uuid, String nickname, String ip) {
        this.uuid = uuid;
        this.nickname = nickname;
        this.ip = ip;
    }

    public boolean isAuthed() {
        return state == State.OK;
    }
}
