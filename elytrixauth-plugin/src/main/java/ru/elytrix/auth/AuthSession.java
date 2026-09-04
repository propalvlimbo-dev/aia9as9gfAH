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
    /** epoch-сек, когда код привязки истечёт (для отсчёта в actionbar). */
    public volatile long linkExpires = 0;
    /** когда показан баннер «аккаунт привязан» (эпоха-мс; 0 = нет). */
    public volatile long linkDoneAt = 0;
    /** анти-спам actionbar в состоянии OK (эпоха-мс). */
    public volatile long okTipAt = 0;
    /** живой bossbar (null, если не поддерживается прокси). */
    public volatile Visual.BossBar bar = null;
    /** последний текст боссбара, чтобы не спамить пакеты. */
    public volatile String barText = null;

    /** для анти-спама actionbar в тике. */
    public volatile long lastTipAt = 0;
    /** последний текст, отправленный в actionbar (не повторяем, пока не изменился). */
    public volatile String lastActionbar = null;
    /** для анти-спама сообщений о блокировке чата. */
    public volatile long chatTipAt = 0;
    /** следующее периодическое напоминание «введи пароль» (эпоха-мс). */
    public volatile long remindAt = 0;
    /** защита от двойного connect-запроса (эпоха-мс последнего). */
    public volatile long lastConnectAt = 0;
    /** когда игрок авторизовался (для одноразовой подсказки про Telegram). */
    public volatile long authedAt = 0;
    /** одноразовая подсказка про /addtg уже показана. */
    public volatile boolean tgHintShown = false;
    /** первичный вход на сервер в этой сессии — UI/приветствие ещё не показаны. */
    public volatile boolean joinUiShown = false;
    /** показ первого UI уже запланирован (отложен ~1 сек после подключения). */
    public volatile boolean uiPending = false;
    /** «Добро пожаловать» в actionbar уже запланирован для игрового мира. */
    public volatile boolean welcomeScheduled = false;
    /** когда в последний раз показывали title (чтобы он не исчезал с экрана). */
    public volatile long lastTitleAt = 0;
    /** epoch-мс момента подключения к текущему серверу (для смягчения киков). */
    public volatile long joinedServerAt = 0;

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
