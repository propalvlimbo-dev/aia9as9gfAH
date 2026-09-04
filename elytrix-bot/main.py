"""ElytrixBot: Telegram-бот Elytrix (панель управления аккаунтом).

Бот общается с плагином ElytrixAuth по HTTP API (см. api.py):

  /addtg в игре -> код -> боту /link <код> -> POST /api/link

После привязки внизу чата появляется reply-клавиатура - «главная страница»
с разделами (папками). Кнопки сгруппированы по 2 в ряд:

  🛡️ Безопасность
      🔑 Сменить пароль  |  ⛏ Кикнуть
      🔐/🔓 2FA          |  🚪 Все сессии
      📜 История входов  |  🧊/🔥 Заморозить(-ся)
      🔔/🔕 Уведомления  |  ⬅️ Главная
  🎮 Игровой процесс
      👤 Профиль  |  ⚖️ Наказания
      🎉 Ивенты   |  🆘 Поддержка
      📋 Правила  |  ⬅️ Главная
  🎁 Награды и бонусы (только для подписчиков канала @elytrix_ru)
      🎁 Дневной бонус  |  🏷️ Промокоды
      📢 Канал Elytrix   |  ⬅️ Главная

Входы и уведомления:
    2FA вкл:  плагин создаёт login_request -> inline «Войти / Отклонить».
    2FA выкл: сообщение «Вход в аккаунт» (ник/IP/время) + inline «⛏ Кикнуть».

Один Telegram = один аккаунт.
"""
import asyncio
import html
import logging
import time
from datetime import datetime
from zoneinfo import ZoneInfo

from aiogram import Bot, Dispatcher, F
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.filters import Command, CommandStart
from aiogram.types import (CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup,
                           KeyboardButton, Message, ReplyKeyboardMarkup,
                           ReplyKeyboardRemove)

from api import ApiError, ElytrixApi
from config import Config

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s: %(message)s")
log = logging.getLogger("elytrix")

CFG = Config()
problems = CFG.validate()
if problems:
    raise SystemExit("Конфигурация неполная:\n  - " + "\n  - ".join(problems))

api = ElytrixApi(CFG.API_BASE, CFG.API_KEY)
bot = Bot(token=CFG.BOT_TOKEN, default=DefaultBotProperties(parse_mode=ParseMode.HTML))
dp = Dispatcher()

TZ = ZoneInfo("Europe/Moscow")
CHANNEL_URL = "https://t.me/elytrix_ru"

# id запросов входа, которым уже отправили кнопку (чтобы не дублировать)
_sent: set[int] = set()

# Ожидание нового пароля: {tg_id: {"nickname": str, "until": epoch}}
_pending_pw: dict[int, dict] = {}
PW_TTL_SEC = 300

# ------------------------------------------------------------------ разделы (папки)

SEC = "🛡️ Безопасность"
GAME = "🎮 Игровой процесс"
REW = "🎁 Награды и бонусы"
BACK = "⬅️ Главная"

# Безопасность
KICK = "⛏ Кикнуть"
PW = "🔑 Сменить пароль"
TFA_ON = "🔐 Включить 2FA"
TFA_OFF = "🔓 Выключить 2FA"
SESS = "🚪 Все сессии"
HIST = "📜 История входов"
FRZ = "🧊 Заморозить"
UNFRZ = "🔥 Разморозить"
NOTIF_ON = "🔔 Включить увед."
NOTIF_OFF = "🔕 Выключить увед."

# Игровой процесс
PROF = "👤 Профиль"
EVENTS = "🎉 Ивенты"
PUN = "⚖️ Наказания"
SUPP = "🆘 Поддержка"
RULES = "📋 Правила"

# Награды и бонусы
DAILY = "🎁 Дневной бонус"
PROMO = "🏷️ Промокоды"
CHANNEL = "📢 Канал Elytrix"

# Все текущие кнопки (для «это не пароль, не меню»-фильтра)
ALL_LABELS = {
    SEC, GAME, REW, BACK,
    KICK, PW, TFA_ON, TFA_OFF, SESS, HIST, FRZ, UNFRZ, NOTIF_ON, NOTIF_OFF,
    PROF, EVENTS, PUN, SUPP, RULES,
    DAILY, PROMO, CHANNEL,
}

# Кнопки прошлых версий бота: нажимает -> обрабатываем как новую
OLD_TO_NEW = {
    "⛏ Кикнуть с сервера": KICK,
    "🚪 Завершить все сессии": SESS,
    "🧊 Заморозить аккаунт": FRZ,
    "🔥 Разморозить аккаунт": UNFRZ,
    "🔔 Включить уведомления": NOTIF_ON,
    "🔕 Выключить уведомления": NOTIF_OFF,
    "👤 Профиль и статистика": PROF,
    "🎉 Активные ивенты": EVENTS,
    "⚖️ История наказаний": PUN,
    "🎁 Ежедневный бонус": DAILY,
}
OLD_LABELS = set(OLD_TO_NEW)

# Коды ошибок (плагин) -> человеческий текст
PW_ERR_TEXT = {
    "too_short": "слишком короткий",
    "too_long": "слишком длинный (больше 64 символов)",
    "like_nick": "не должен совпадать с ником аккаунта",
    "same_chars": "слишком простой (все символы одинаковые)",
    "player_not_found": "аккаунт не найден на сервере",
    "not_yours": "аккаунт больше не привязан к этому Telegram",
}
LINK_ERR_TEXT = {
    "invalid_or_expired_code": "Код не найден или уже использован.\n"
                               "Запусти /addtg в игре ещё раз - придёт новый код.",
    "tg_already_linked": "У этого Telegram уже привязан аккаунт - к одному Telegram "
                         "можно привязать только один аккаунт.",
}


# ------------------------------------------------------------------ helpers

def esc(v) -> str:
    return html.escape(str(v), quote=False)


def fmt_ts(ts) -> str:
    try:
        return datetime.fromtimestamp(int(ts), tz=TZ).strftime("%d.%m.%y %H:%M")
    except Exception:
        return "-"


async def get_accounts(chat_id: int) -> list | None:
    """Аккаунты пользователя. None - сервер недоступен."""
    try:
        return await api.accounts(chat_id)
    except ApiError as e:
        log.warning("accounts error: %s", e)
        return None


def acc_lines(acc: dict) -> list[str]:
    where = "в игре" if acc["online"] else "не в игре"
    twofa = "включена" if acc["tg2fa"] else "выключена"
    notify = "включены" if acc["notify"] else "выключены"
    frozen = "АКТИВНА" if acc["frozen"] else "нет"
    return [
        f"┃ Аккаунт: <b>{esc(acc['nickname'])}</b>",
        f"┃ ● Статус: {where}",
        f"┃ ● 2FA: {twofa}",
        f"┃ ● Уведомления о входах: {notify}",
        f"┃ ● Заморозка: {frozen}",
    ]


def no_account_text() -> str:
    return ("☁ <b>ᴇʟʏᴛʀɪx</b>\n\n"
            "┃ К этому Telegram не привязан ни один аккаунт.\n"
            "┃ ● Зайди на сервер и напиши в игре <code>/addtg</code>\n"
            "┃ ● Полученный код отправь сюда: <code>/link &lt;код&gt;</code>\n\n"
            "После привязки здесь появится панель управления.")


async def fetch_single(chat_id: int) -> tuple[dict | None, list | None]:
    """(аккаунт, None) если ровно один; (None, accounts) иначе (пусто/много/None-ошибка)."""
    accounts = await get_accounts(chat_id)
    if accounts is None:
        return None, None
    if len(accounts) == 1:
        return accounts[0], None
    return None, accounts


async def send_no_link(chat_id: int) -> None:
    await bot.send_message(chat_id, no_account_text(), reply_markup=ReplyKeyboardRemove())


async def send_legacy_multi(chat_id: int) -> None:
    await bot.send_message(
        chat_id,
        "☁ <b>ᴇʟʏᴛʀɪx</b>\n\n"
        "┃ У тебя привязано несколько аккаунтов (старая версия).\n"
        "┃ Сейчас действует правило: <b>один Telegram - один аккаунт</b>.\n"
        "┃ ● Напиши администратору, какой аккаунт оставить - лишние снимут.",
        reply_markup=ReplyKeyboardRemove())


async def send_main(chat_id: int, result: str | None = None) -> None:
    accounts = await get_accounts(chat_id)
    if accounts is None:
        await bot.send_message(chat_id, "⚠️ Сервер недоступен. Попробуй ещё раз.")
        return
    if not accounts:
        await send_no_link(chat_id)
        return
    if len(accounts) > 1:
        await send_legacy_multi(chat_id)
        return
    acc = accounts[0]
    lines = ["☁ <b>ᴇʟʏᴛʀɪx</b> - панель управления", ""]
    lines += acc_lines(acc)
    lines += ["", "Выбери раздел кнопками внизу чата:"]
    if result:
        lines += ["", result]
    await bot.send_message(chat_id, "\n".join(lines), reply_markup=kb_main())


async def send_security(chat_id: int, result: str | None = None) -> None:
    acc, rest = await fetch_single(chat_id)
    if acc is None:
        if rest is None:
            await bot.send_message(chat_id, "⚠️ Сервер недоступен. Попробуй ещё раз.")
        elif not rest:
            await send_no_link(chat_id)
        else:
            await send_legacy_multi(chat_id)
        return
    lines = ["🛡️ <b>Безопасность</b>"]
    lines += ["", "Кнопки внизу чата:"]
    if result:
        lines += ["", result]
    await bot.send_message(chat_id, "\n".join(lines), reply_markup=kb_security(acc))


async def send_game(chat_id: int, result: str | None = None) -> None:
    acc, rest = await fetch_single(chat_id)
    if acc is None:
        if rest is None:
            await bot.send_message(chat_id, "⚠️ Сервер недоступен. Попробуй ещё раз.")
        elif not rest:
            await send_no_link(chat_id)
        else:
            await send_legacy_multi(chat_id)
        return
    lines = ["🎮 <b>Игровой процесс</b>", "", "Кнопки внизу чата:"]
    if result:
        lines += ["", result]
    await bot.send_message(chat_id, "\n".join(lines), reply_markup=kb_game())


async def send_rewards(chat_id: int, result: str | None = None) -> None:
    acc, rest = await fetch_single(chat_id)
    if acc is None:
        if rest is None:
            await bot.send_message(chat_id, "⚠️ Сервер недоступен. Попробуй ещё раз.")
        elif not rest:
            await send_no_link(chat_id)
        else:
            await send_legacy_multi(chat_id)
        return
    lines = ["🎁 <b>Награды и бонусы</b>", "", "Кнопки внизу чата:"]
    if result:
        lines += ["", result]
    await bot.send_message(chat_id, "\n".join(lines), reply_markup=kb_rewards())


def drop_pending_pw(tg_id: int) -> None:
    _pending_pw.pop(tg_id, None)


# ------------------------------------------------------------------ клавиатуры

def kb_main() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        keyboard=[[KeyboardButton(text=SEC), KeyboardButton(text=GAME)],
                  [KeyboardButton(text=REW)]],
        resize_keyboard=True, is_persistent=True,
        input_field_placeholder="Панель Elytrix")


def kb_security(acc: dict) -> ReplyKeyboardMarkup:
    twofa = TFA_OFF if acc["tg2fa"] else TFA_ON
    frozen = UNFRZ if acc["frozen"] else FRZ
    notif = NOTIF_OFF if acc["notify"] else NOTIF_ON
    return ReplyKeyboardMarkup(
        keyboard=[[KeyboardButton(text=PW), KeyboardButton(text=KICK)],
                  [KeyboardButton(text=twofa), KeyboardButton(text=SESS)],
                  [KeyboardButton(text=HIST), KeyboardButton(text=frozen)],
                  [KeyboardButton(text=notif), KeyboardButton(text=BACK)]],
        resize_keyboard=True, is_persistent=True,
        input_field_placeholder="Панель Elytrix - безопасность")


def kb_game() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        keyboard=[[KeyboardButton(text=PROF), KeyboardButton(text=PUN)],
                  [KeyboardButton(text=EVENTS), KeyboardButton(text=SUPP)],
                  [KeyboardButton(text=RULES), KeyboardButton(text=BACK)]],
        resize_keyboard=True, is_persistent=True,
        input_field_placeholder="Панель Elytrix - игровой процесс")


def kb_rewards() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        keyboard=[[KeyboardButton(text=DAILY), KeyboardButton(text=PROMO)],
                  [KeyboardButton(text=CHANNEL), KeyboardButton(text=BACK)]],
        resize_keyboard=True, is_persistent=True,
        input_field_placeholder="Панель Elytrix - награды")


def kb_subscribe() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[[
        InlineKeyboardButton(text="📢 Подписаться", url=CHANNEL_URL),
    ], [
        InlineKeyboardButton(text="✅ Я подписался", callback_data="sub:chk"),
    ]])


# ------------------------------------------------------------------ подписка на канал (Награды)

async def check_subscribed(tg_id: int) -> bool | None:
    """True - доступ есть (подписан).

    False - не подписан; None - не удалось проверить (CHANNEL_ID пуст,
    бот не админ канала и т.п.). Раздел закрыт во всех случаях, кроме True.
    """
    channel = (CFG.CHANNEL_ID or "").strip()
    if not channel:
        log.warning("CHANNEL_ID пуст - «Награды» закрыты для всех.")
        return None
    try:
        member = await bot.get_chat_member(chat_id=channel, user_id=tg_id)
    except Exception as e:
        log.warning("проверка подписки %s для tg=%s: %s", channel, tg_id, e)
        return None
    return member.status in ("creator", "administrator", "member", "restricted")


def subscribe_block_text() -> str:
    channel = (CFG.CHANNEL_ID or "").strip().lstrip("@")
    if not channel:
        return ("🎁 <b>Награды и бонусы</b> - раздел закрыт\n\n"
                "┃ ● Проверка подписки на канал не настроена у бота.\n"
                "┃ ● Напиши администратору - он включит её (CHANNEL_ID).")
    return ("🎁 <b>Награды и бонусы</b> - раздел для подписчиков канала\n\n"
            f"┃ ● Подпишись на <b>@{esc(channel)}</b> - и раздел откроется.\n"
            "┃ ● Уже подписан? Нажми «✅ Я подписался».")


async def block_rewards(chat_id: int) -> None:
    await bot.send_message(chat_id, subscribe_block_text(),
                           reply_markup=kb_subscribe())


async def ensure_rewards_access(chat_id: int, tg_id: int) -> bool:
    """True - можно показать содержимое наград; False - раздел закрыт."""
    st = await check_subscribed(tg_id)
    if st is True:
        return True
    if st is False:
        await block_rewards(chat_id)
        return False
    await block_rewards(chat_id)
    await bot.send_message(
        chat_id,
        "⚠️ Не удалось проверить подписку на канал: бот должен быть добавлен "
        "в канал <b>администратором</b>, а в .env задан CHANNEL_ID.")
    return False


# ------------------------------------------------------------------ команды

@dp.message(CommandStart())
async def cmd_start(m: Message) -> None:
    if m.from_user is None:
        return
    drop_pending_pw(m.from_user.id)
    accounts = await get_accounts(m.chat.id)
    if accounts is None:
        await m.answer("⚠️ Сервер недоступен. Попробуй ещё раз.")
        return
    if not accounts:
        await m.answer(
            "☁ <b>ᴇʟʏᴛʀɪx</b> - привязка аккаунта к Telegram\n\n"
            "┃ ● Зайди на сервер и напиши в игре: <code>/addtg</code>\n"
            "┃ ● Придёт код - отправь его сюда: <code>/link &lt;код&gt;</code>\n\n"
            "После привязки откроется панель управления:\n"
            "┃ 🛡️ Безопасность · 🎮 Игровой процесс · 🎁 Награды и бонусы")
        return
    await send_main(m.chat.id)  # одно сообщение: панель вместо приветствия


@dp.message(Command("help"))
async def cmd_help(m: Message) -> None:
    if m.from_user is None:
        return
    drop_pending_pw(m.from_user.id)
    await send_main(m.chat.id)


@dp.message(Command("menu"))
async def cmd_menu(m: Message) -> None:
    if m.from_user is None:
        return
    drop_pending_pw(m.from_user.id)
    await send_main(m.chat.id)


@dp.message(Command("link"))
async def cmd_link(m: Message) -> None:
    if m.from_user is None:
        return
    drop_pending_pw(m.from_user.id)
    args = m.text.split()
    if len(args) != 2 or not args[1].isdigit():
        await m.answer("Отправь код из игры так: <code>/link 123456</code>")
        return
    try:
        nickname, err_code = await api.link(args[1], m.from_user.id)
    except ApiError as e:
        log.warning("link error: %s", e)
        await m.answer("⚠️ Не удалось связаться с сервером. Попробуй через минуту.")
        return
    if nickname is None:
        reason = LINK_ERR_TEXT.get(err_code, "Код не найден или уже использован. "
                                            "Запусти <code>/addtg</code> в игре ещё раз.")
        await m.answer("❌ " + reason)
        return
    # одно сообщение: привязанный аккаунт + панель
    await send_main(m.chat.id, result=(
        f"✅ Аккаунт <b>{esc(nickname)}</b> привязан к твоему Telegram!\n\n"
        "┃ ● 2FA по умолчанию выключена - при входе пришлю уведомление.\n"
        "┃ ● Включить 2FA, кик, смену пароля и другое можно кнопками панели внизу.\n"
        "┃ ● Если это не твой аккаунт - напиши администратору."))


@dp.message(Command("unlink"))
async def cmd_unlink(m: Message) -> None:
    if m.from_user is None:
        return
    drop_pending_pw(m.from_user.id)
    await m.answer(
        "🔓 Отвязка аккаунта от Telegram пока делается на сервере.\n"
        "Напиши администратору ник - он снимет привязку вручную.")


# ------------------------------------------------------------------ меню (reply-кнопки)

async def kick_action(m: Message, phrase_online: str, phrase_offline: str) -> None:
    """Общий кик: игрок онлайн - фраза phrase_online, офлайн - phrase_offline."""
    if m.from_user is None:
        return
    acc, rest = await fetch_single(m.chat.id)
    if acc is None:
        if rest is not None and not rest:
            await send_no_link(m.chat.id)
        return
    nick = acc["nickname"]
    try:
        data = await api.kick(nick, m.from_user.id)
    except ApiError as e:
        log.warning("kick error: %s", e)
        await m.answer("⚠️ Сервер недоступен. Попробуй ещё раз.")
        return
    if not data.get("ok"):
        err = data.get("error")
        if err in ("not_yours", "player_not_found"):
            await send_security(m.chat.id, f"❌ <b>{esc(nick)}</b> больше не привязан "
                                           f"к этому Telegram.")
            return
        log.warning("kick error: %s", err)
        await m.answer("⚠️ Не получилось. Попробуй ещё раз.")
        return
    result = phrase_online if data.get("online") else phrase_offline
    await send_security(m.chat.id, result)


async def on_any_label(m: Message, label: str) -> None:
    """Действия по кнопкам меню (label - каноническое имя кнопки)."""
    if m.from_user is None:
        return
    drop_pending_pw(m.from_user.id)

    if label in (SEC,):
        await send_security(m.chat.id)
    elif label in (GAME,):
        await send_game(m.chat.id)
    elif label in (REW,):
        ok = await ensure_rewards_access(m.chat.id, m.from_user.id)
        if ok:
            await send_rewards(m.chat.id)
    elif label == BACK:
        await send_main(m.chat.id)

    elif label == KICK:
        await kick_action(m,
                          f"✅ <b>Кик выполнен.</b> Игрок выгнан с сервера, сессия сброшена - "
                          f"следующий вход по паролю.",
                          f"⛏ Игрок сейчас не в игре. Сессия сброшена - при следующем входе "
                          f"понадобится пароль.")
    elif label == SESS:
        await kick_action(m,
                          f"✅ <b>Все сессии завершены.</b> Игрок выведен из игры. "
                          f"Следующий вход - по паролю.",
                          f"✅ <b>Все сессии завершены.</b> Игрок был не в игре. "
                          f"Следующий вход - по паролю.")
    elif label == PW:
        await start_password_flow(m)
    elif label in (TFA_ON, TFA_OFF):
        await toggle_2fa(m, want_on=(label == TFA_ON))
    elif label in (FRZ, UNFRZ):
        await toggle_freeze(m, want_freeze=(label == FRZ))
    elif label in (NOTIF_ON, NOTIF_OFF):
        await toggle_notify(m, want_on=(label == NOTIF_ON))
    elif label == HIST:
        await show_history(m)
    elif label == PROF:
        await show_profile(m)
    elif label == EVENTS:
        await show_events(m)
    elif label == PUN:
        await m.answer(
            "⚖️ <b>История наказаний</b>\n\n"
            "┃ ● Раздел подключим чуть позже - у Elytrix свой плагин наказаний.\n"
            "┃ ● Здесь можно будет увидеть свои баны/муты/варны и их причины.")
    elif label == SUPP:
        kb = InlineKeyboardMarkup(inline_keyboard=[[
            InlineKeyboardButton(text="✉️ @Elytrix_Help", url="https://t.me/Elytrix_Help"),
        ]])
        await m.answer(
            "🆘 <b>Поддержка</b>\n\n"
            "┃ ● Связаться с поддержкой - кнопка ниже.\n"
            "┃ ● Отвечаем по мере возможности, не спамь, пожалуйста.",
            reply_markup=kb)
    elif label == RULES:
        kb = InlineKeyboardMarkup(inline_keyboard=[[
            InlineKeyboardButton(text="🌐 Elytrix - правила и донат", url="https://elytrix.pw/"),
        ]])
        await m.answer(
            "📋 <b>Правила сервера</b>\n\n"
            "┃ ● Полная сводка правил и донат-магазин - кнопка ниже.\n"
            "┃ ● Незнание правил не освобождает от ответственности.",
            reply_markup=kb)
    elif label == DAILY:
        if await ensure_rewards_access(m.chat.id, m.from_user.id):
            kb = InlineKeyboardMarkup(inline_keyboard=[[
                InlineKeyboardButton(text="📢 Канал Elytrix", url=CHANNEL_URL),
            ]])
            await m.answer(
                "🎁 <b>Ежедневный бонус</b>\n\n"
                "┃ ● Раздел появится вместе с системой ElytrixFree (её пока нет).\n"
                "┃ ● Бонус выдаётся <b>только подписчикам канала</b> - подписка уже "
                "подтверждена, не пропусти запуск.",
                reply_markup=kb)
    elif label == PROMO:
        if await ensure_rewards_access(m.chat.id, m.from_user.id):
            kb = InlineKeyboardMarkup(inline_keyboard=[[
                InlineKeyboardButton(text="📢 Канал Elytrix", url=CHANNEL_URL),
            ]])
            await m.answer(
                "🏷️ <b>Промокоды</b>\n\n"
                "┃ ● Активация секретных кодов заработает вместе с ElytrixFree (пока заглушка).\n"
                "┃ ● Коды будут выходить в канале - <b>подписка обязательна</b> (кнопка ниже).",
                reply_markup=kb)
    elif label == CHANNEL:
        kb = InlineKeyboardMarkup(inline_keyboard=[[
            InlineKeyboardButton(text="📢 Подписаться", url=CHANNEL_URL),
        ]])
        await m.answer(
            f"📢 <b>Канал Elytrix</b>\n\n"
            f"┃ ● Новости, промокоды, ежедневные бонусы - {CHANNEL_URL}\n"
            "┃ ● Подписка обязательна для раздела «Награды и бонусы».",
            reply_markup=kb)


@dp.message(F.text.in_(ALL_LABELS))
async def on_menu_label(m: Message) -> None:
    await on_any_label(m, m.text)


@dp.message(F.text.in_(OLD_LABELS))
async def on_old_menu_label(m: Message) -> None:
    await on_any_label(m, OLD_TO_NEW[m.text])


# ------------------------------------------------------------------ действия (общие для кнопок)

async def start_password_flow(m: Message) -> None:
    if m.from_user is None:
        return
    acc, rest = await fetch_single(m.chat.id)
    if acc is None:
        if rest is not None and not rest:
            await send_no_link(m.chat.id)
        return
    nick = acc["nickname"]
    _pending_pw[m.from_user.id] = {"nickname": nick, "until": time.time() + PW_TTL_SEC}
    await m.answer(
        f"🔑 Отправь <b>новый пароль</b> для аккаунта <b>{esc(nick)}</b> одним сообщением.\n\n"
        "┃ ● Старый пароль и все сессии сразу потеряют силу.\n"
        "┃ ● Если игрок сейчас в игре - его выкинет (пусть заходит с новым паролем).\n"
        "┃ ● Отменить ввод: <code>/menu</code> или любая кнопка меню.")


async def toggle_2fa(m: Message, want_on: bool) -> None:
    if m.from_user is None:
        return
    acc, rest = await fetch_single(m.chat.id)
    if acc is None:
        if rest is not None and not rest:
            await send_no_link(m.chat.id)
        return
    nick = acc["nickname"]
    if acc["tg2fa"] == want_on:
        await send_security(m.chat.id,
                            f"ℹ️ 2FA для <b>{esc(nick)}</b> уже "
                            f"{'включена' if want_on else 'выключена'}.")
        return
    try:
        data = await api.toggle2fa(nick, m.from_user.id)
    except ApiError as e:
        log.warning("toggle2fa error: %s", e)
        await m.answer("⚠️ Сервер недоступен. Попробуй ещё раз.")
        return
    if not data.get("ok"):
        err = data.get("error")
        if err in ("not_yours", "player_not_found"):
            await send_security(m.chat.id,
                                f"❌ <b>{esc(nick)}</b> больше не привязан к этому Telegram.")
            return
        log.warning("toggle2fa error: %s", err)
        await m.answer("⚠️ Не получилось. Попробуй ещё раз.")
        return
    if data.get("tg2fa"):
        result = (f"🔐 2FA для <b>{esc(nick)}</b> <b>включена</b>. Теперь при входе "
                  f"(после пароля) нужно будет подтвердить вход кнопкой здесь.")
    else:
        result = (f"🔓 2FA для <b>{esc(nick)}</b> <b>выключена</b>. При входе буду "
                  f"присылать только уведомление.")
    await send_security(m.chat.id, result)


async def toggle_freeze(m: Message, want_freeze: bool) -> None:
    if m.from_user is None:
        return
    acc, rest = await fetch_single(m.chat.id)
    if acc is None:
        if rest is not None and not rest:
            await send_no_link(m.chat.id)
        return
    nick = acc["nickname"]
    if acc["frozen"] == want_freeze:
        await send_security(m.chat.id,
                            f"ℹ️ Аккаунт <b>{esc(nick)}</b> уже "
                            f"{'заморожен' if want_freeze else 'разморожен'}.")
        return
    try:
        data = await api.freeze(nick, m.from_user.id, want_freeze)
    except ApiError as e:
        log.warning("freeze error: %s", e)
        await m.answer("⚠️ Сервер недоступен. Попробуй ещё раз.")
        return
    if not data.get("ok"):
        err = data.get("error")
        if err in ("not_yours", "player_not_found"):
            await send_security(m.chat.id,
                                f"❌ <b>{esc(nick)}</b> больше не привязан к этому Telegram.")
            return
        log.warning("freeze error: %s", err)
        await m.answer("⚠️ Не получилось. Попробуй ещё раз.")
        return
    if data.get("frozen"):
        extra = (" Игрок кикнут с сервера." if data.get("kicked") else " Игрок был не в игре.")
        result = (f"🧊 Аккаунт <b>{esc(nick)}</b> <b>заморожен</b> - вход запрещён до "
                  f"разморозки.{extra}")
    else:
        result = f"🔥 Аккаунт <b>{esc(nick)}</b> <b>разморожен</b> - можно входить."
    await send_security(m.chat.id, result)


async def toggle_notify(m: Message, want_on: bool) -> None:
    if m.from_user is None:
        return
    acc, rest = await fetch_single(m.chat.id)
    if acc is None:
        if rest is not None and not rest:
            await send_no_link(m.chat.id)
        return
    nick = acc["nickname"]
    if acc["notify"] == want_on:
        await send_security(m.chat.id,
                            f"ℹ️ Уведомления для <b>{esc(nick)}</b> уже "
                            f"{'включены' if want_on else 'выключены'}.")
        return
    try:
        data = await api.notify(nick, m.from_user.id, want_on)
    except ApiError as e:
        log.warning("notify error: %s", e)
        await m.answer("⚠️ Сервер недоступен. Попробуй ещё раз.")
        return
    if not data.get("ok"):
        err = data.get("error")
        if err in ("not_yours", "player_not_found"):
            await send_security(m.chat.id,
                                f"❌ <b>{esc(nick)}</b> больше не привязан к этому Telegram.")
            return
        log.warning("notify error: %s", err)
        await m.answer("⚠️ Не получилось. Попробуй ещё раз.")
        return
    if data.get("notify"):
        result = (f"🔔 Уведомления о входах для <b>{esc(nick)}</b> <b>включены</b> - "
                  f"буду сообщать о каждом входе в игру.")
    else:
        result = (f"🔕 Уведомления о входах для <b>{esc(nick)}</b> <b>выключены</b> - "
                  f"сообщения о входах больше не приходят.")
    await send_security(m.chat.id, result)


async def show_history(m: Message) -> None:
    if m.from_user is None:
        return
    acc, rest = await fetch_single(m.chat.id)
    if acc is None:
        if rest is not None and not rest:
            await send_no_link(m.chat.id)
        return
    nick = acc["nickname"]
    try:
        rows = await api.logins(nick, m.from_user.id)
    except ApiError as e:
        log.warning("logins error: %s", e)
        await m.answer("⚠️ Сервер недоступен. Попробуй ещё раз.")
        return
    lines = [f"📜 <b>История входов</b> - {esc(nick)}", ""]
    if not rows:
        lines.append("┃ Записей пока нет.")
    else:
        for r in rows:
            ip = r.get("ip") or "?"
            lines.append(f"┃ ● {fmt_ts(r.get('ts'))} - <code>{esc(ip)}</code>")
        lines.append("")
        lines.append("┃ Показаны последние 10 входов.")
    await m.answer("\n".join(lines))


async def show_profile(m: Message) -> None:
    if m.from_user is None:
        return
    acc, rest = await fetch_single(m.chat.id)
    if acc is None:
        if rest is not None and not rest:
            await send_no_link(m.chat.id)
        return
    nick = acc["nickname"]
    where = "в игре" if acc["online"] else "не в игре"
    try:
        data = await api.profile(nick, m.from_user.id)
    except ApiError as e:
        log.warning("profile error: %s", e)
        await m.answer("⚠️ Сервер недоступен. Попробуй ещё раз.")
        return

    d = data.get("donate", {})
    if d.get("available"):
        prefix = (d.get("prefix") or "").strip()
        group = (d.get("group") or "").strip()
        if prefix:
            donate = f"<code>{esc(prefix)}</code>"
            if group and group.lower() not in prefix.lower():
                donate += f" <i>({esc(group)})</i>"
        elif group:
            donate = f"группа <code>{esc(group)}</code>"
        else:
            donate = "данные пусты"
    else:
        donate = "недоступно"

    c = data.get("coins", {})
    if c.get("available"):
        coins = f"<code>{int(c.get('value') or 0):,}</code>".replace(",", " ")
    else:
        coins = "недоступно"

    pt = data.get("playtime", {})
    if pt.get("available"):
        minutes = int(pt.get("minutes") or 0)
        h, mm = divmod(minutes, 60)
        playtime = f"<b>{h} ч {mm} мин</b>" if h else f"<b>{mm} мин</b>"
    else:
        playtime = "недоступно"

    meta = data.get("meta", {})
    note = None
    if meta.get("from_cache"):
        age_min = max(0, int(meta.get("cache_age_ms") or 0) // 60000)
        note = f"данные из кэша ({age_min} мин назад)"
    elif not d.get("available") or not c.get("available"):
        if not meta.get("lp_proxy") and meta.get("relay") != "ok":
            note = ("не удалось достучаться до игрового сервера: нужен мост "
                    "ElytrixAuthBridge.jar и хотя бы один игрок онлайн на нём")
        elif not d.get("available"):
            note = "LuckPerms не отвечает (не установлен на прокси или игровом сервере)"
        elif not c.get("available"):
            note = "PlayerPoints не отвечает (не установлен на игровом сервере)"

    lines = [f"👤 <b>Профиль и статистика</b> - {esc(nick)}", "",
             f"┃ ● Статус: {where}",
             f"┃ ● Донат (LuckPerms): {donate}",
             f"┃ ● Коины (PlayerPoints): {coins}",
             f"┃ ● Наигранное время: {playtime}"]
    if note:
        lines += ["", f"┃ {esc(note)}"]
    await m.answer("\n".join(lines))


async def show_events(m: Message) -> None:
    if m.from_user is None:
        return
    acc, rest = await fetch_single(m.chat.id)
    if acc is None:
        if rest is not None and not rest:
            await send_no_link(m.chat.id)
        return
    nick = acc["nickname"]
    try:
        data = await api.events(nick, m.from_user.id)
    except ApiError as e:
        log.warning("events error: %s", e)
        await m.answer("⚠️ Сервер недоступен. Попробуй ещё раз.")
        return
    if not data.get("lp"):
        await m.answer(
            "🎉 <b>Активные ивенты</b>\n\n"
            "┃ ● Проверка привилегии недоступна (LuckPerms не отвечает).\n"
            "┃ ● Раздел доступен только игрокам с привилегией <b>Elder</b>.\n\n"
            "Попробуй позже."
        )
        return
    if data.get("elder"):
        await m.answer(
            "🎉 <b>Активные ивенты</b>\n\n"
            "┃ ● Привилегия Elder подтверждена ✔\n"
            "┃ ● Активных ивентов сейчас нет - раздел готовится."
        )
    else:
        await m.answer(
            "🎉 <b>Активные ивенты</b>\n\n"
            "┃ ● Раздел доступен только игрокам с привилегией <b>Elder</b>.\n"
            "┃ ● У тебя её пока нет - купить можно на сайте elytrix.pw"
        )


# Любое другое текстовое сообщение: если ждём пароль - это он
@dp.message(F.text & ~F.text.startswith("/") & ~F.text.in_(ALL_LABELS | OLD_LABELS))
async def on_any_text(m: Message) -> None:
    if m.from_user is None:
        return
    st = _pending_pw.get(m.from_user.id)
    if st is None:
        return
    nick = st["nickname"]
    if time.time() > st["until"]:
        drop_pending_pw(m.from_user.id)
        await m.answer("⏰ Время на ввод пароля вышло. Нажми «Сменить пароль» ещё раз.")
        return
    password = m.text.strip()
    try:
        data = await api.change_password(nick, m.from_user.id, password)
    except ApiError as e:
        log.warning("change password error: %s", e)
        drop_pending_pw(m.from_user.id)
        await m.answer("⚠️ Сервер недоступен. Нажми «Сменить пароль» ещё раз.")
        return
    if data.get("ok"):
        drop_pending_pw(m.from_user.id)
        extra = (" Игрок был онлайн - его выкинуло с сервера."
                 if data.get("kicked")
                 else " Игрок был не в игре.")
        await send_main(m.chat.id, result=(
            f"✅ Пароль аккаунта <b>{esc(nick)}</b> изменён.\n\n"
            "┃ ● Старый пароль больше не действует, все сессии сброшены." + extra + "\n"
            "┃ ● При следующем входе в игру используй новый пароль."))
        return
    err = str(data.get("error", "unknown"))
    if err in ("not_yours", "player_not_found"):
        drop_pending_pw(m.from_user.id)
        await m.answer(f"❌ Аккаунт <b>{esc(nick)}</b> больше не привязан к этому Telegram.")
        await send_main(m.chat.id)
        return
    reason = PW_ERR_TEXT.get(err, err or "не подходит")
    await m.answer(
        f"❌ Пароль для <b>{esc(nick)}</b> не принят ({reason}).\n"
        "Пришли другой пароль или отмени командой <code>/menu</code>."
    )


# ------------------------------------------------------------------ inline: входы, кик из уведомления, подписка

async def owned_account(cq: CallbackQuery) -> dict | None:
    """Аккаунт по uuid из inline-кнопки; проверяем привязку к этому TG."""
    if cq.from_user is None:
        await cq.answer()
        return None
    uuid = cq.data.split(":", 1)[1]
    accounts = await get_accounts(cq.message.chat.id)
    if accounts is None:
        await cq.answer("⚠️ Сервер недоступен, попробуй ещё раз.", show_alert=True)
        return None
    for a in accounts:
        if a["uuid"] == uuid:
            return a
    await cq.answer("Аккаунт больше не привязан.", show_alert=True)
    return None


# Кик прямо из сообщения «Вход в аккаунт» (2FA выключена)
@dp.callback_query(F.data.startswith("ak:"))
async def cb_kick_from_alert(cq: CallbackQuery) -> None:
    await cq.answer()
    acc = await owned_account(cq)
    if acc is None:
        return
    nick = acc["nickname"]
    try:
        data = await api.kick(nick, cq.from_user.id)
    except ApiError as e:
        log.warning("alert-kick error: %s", e)
        await cq.answer("⚠️ Сервер недоступен. Попробуй ещё раз.", show_alert=True)
        return
    if data.get("ok"):
        extra = ("Игрок кикнут с сервера, сессия сброшена."
                 if data.get("online")
                 else "Игрок сейчас не в игре, но сессия сброшена - вход будет по паролю.")
        try:
            await cq.message.edit_text(cq.message.html_text + f"\n\n✅ {extra}",
                                       reply_markup=None)
        except Exception:
            pass
    else:
        await cq.answer("❌ Не получилось кикнуть.", show_alert=True)


# Проверка подписки из сообщения «Награды и бонусы»
@dp.callback_query(F.data == "sub:chk")
async def cb_sub_check(cq: CallbackQuery) -> None:
    if cq.from_user is None:
        await cq.answer()
        return
    st = await check_subscribed(cq.from_user.id)
    if st is True:
        try:
            await cq.message.edit_text("✅ Подписка подтверждена!", reply_markup=None)
        except Exception:
            pass
        await cq.answer()
        await send_rewards(cq.message.chat.id)
    elif st is False:
        await cq.answer("Подписка не найдена - убедись, что подписался на канал.",
                        show_alert=True)
    else:
        await cq.answer("Не удалось проверить подписку (бот должен быть админом канала).",
                        show_alert=True)


@dp.callback_query(F.data.startswith("la:"))
async def cb_approve(cq: CallbackQuery) -> None:
    await _answer_login(cq, "confirm")


@dp.callback_query(F.data.startswith("ld:"))
async def cb_deny(cq: CallbackQuery) -> None:
    await _answer_login(cq, "deny")


@dp.callback_query()
async def cb_stale(cq: CallbackQuery) -> None:
    """Inline-кнопки старых версий (старая «панель» в сообщениях) - больше не нужны."""
    if cq.data.startswith(("kick:", "2fa:", "pw:")):
        await cq.answer("Панель теперь внизу чата - нажми /menu", show_alert=False)


async def _answer_login(cq: CallbackQuery, action: str) -> None:
    req_id = int(cq.data.split(":", 1)[1])
    try:
        ok = await api.resolve(req_id, action)
    except ApiError as e:
        log.warning("resolve error: %s", e)
        await cq.answer("⚠️ Сервер недоступен, попробуй ещё раз.", show_alert=False)
        return
    if not ok:
        await cq.answer("⏰ Запрос устарел или уже обработан.", show_alert=False)
        try:
            await cq.message.edit_text(cq.message.html_text + "\n\n<i>(устарел)</i>",
                                       reply_markup=None)
        except Exception:
            pass
        return
    if action == "confirm":
        await cq.answer("✅ Вход подтверждён!", show_alert=False)
        await cq.message.edit_text("✅ <b>Вход подтверждён.</b> Игрок будет пущен на сервер.",
                                   reply_markup=None)
    else:
        await cq.answer("❌ Вход отклонён.", show_alert=False)
        await cq.message.edit_text("❌ <b>Вход отклонён.</b> Если это был не ты - смени пароль "
                                   "кнопкой в панели (<code>/menu</code>).",
                                   reply_markup=None)


# ------------------------------------------------------------------ поллер

async def poller() -> None:
    """Опрос плагина: ожидающие 2FA-входы (inline) и уведомления о входах."""
    log.info("Прослушивание входов запущено (интервал %.1f c)", CFG.POLL_INTERVAL)
    while True:
        try:
            rows = await api.pending()
            for row in rows:
                req_id = int(row["id"])
                if req_id in _sent:
                    continue
                _sent.add(req_id)
                kb = InlineKeyboardMarkup(inline_keyboard=[[
                    InlineKeyboardButton(text="✅ Войти", callback_data=f"la:{req_id}"),
                    InlineKeyboardButton(text="❌ Отклонить", callback_data=f"ld:{req_id}"),
                ]])
                ip_line = f"IP: <code>{esc(row.get('ip') or '')}</code>\n"
                try:
                    await bot.send_message(
                        int(row["tg_id"]),
                        "🔐 <b>Запрос входа на сервер</b>\n\n"
                        f"Игрок: <b>{esc(row['nickname'])}</b>\n{ip_line}"
                        f"Это ты?",
                        reply_markup=kb)
                except Exception as e:
                    log.warning("send 2fa tg=%s req=%s: %s", row.get("tg_id"), req_id, e)
        except ApiError as e:
            log.warning("pending poll: %s", e)
        except Exception:
            log.exception("poller error")

        try:
            alerts = await api.alerts()
            for al in alerts:
                try:
                    # текст от плагина простой; экранируем на случай спецсимволов в нике/IP
                    text = esc(al.get("text") or "")
                    uuid = al.get("player_uuid")
                    kb = None
                    if uuid:
                        # под уведомлением «Вход в аккаунт» - кнопка «Кикнуть»
                        kb = InlineKeyboardMarkup(inline_keyboard=[[
                            InlineKeyboardButton(text="⛏ Кикнуть", callback_data=f"ak:{uuid}"),
                        ]])
                    await bot.send_message(int(al["tg_id"]), text, reply_markup=kb)
                except Exception as e:
                    log.warning("send alert tg=%s: %s", al.get("tg_id"), e)
        except ApiError as e:
            log.warning("alerts poll: %s", e)
        except Exception:
            log.exception("alerts poller error")

        await asyncio.sleep(CFG.POLL_INTERVAL)


# ------------------------------------------------------------------ main

async def main() -> None:
    await bot.set_my_commands([
        {"command": "link", "description": "Привязать аккаунт по коду из игры (/addtg)"},
        {"command": "menu", "description": "Панель управления аккаунтом"},
        {"command": "help", "description": "Помощь"},
    ])
    ok = await api.health()
    if not ok:
        log.warning("Плагин ElytrixAuth не отвечает (%s). Бот будет работать, но входы/привязка недоступны.",
                    CFG.API_BASE)
    if not (CFG.CHANNEL_ID or "").strip():
        log.warning("CHANNEL_ID пуст - проверка подписки для «Награды и бонусы» выключена.")
    asyncio.create_task(poller())
    log.info("ElytrixBot запущен (API: %s)", CFG.API_BASE)
    await dp.start_polling(bot)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except (KeyboardInterrupt, SystemExit):
        log.info("Остановлен")
