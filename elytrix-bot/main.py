"""ElytrixBot: Telegram-бот привязки аккаунтов Elytrix.

Бот общается с плагином ElytrixAuth по HTTP API (см. api.py):

  /addtg в игре -> код -> боту /link <код> -> POST /api/link
  после привязки внизу чата появляется REPLY-КЛАВИАТУРА («панель»), которая
  видна всегда над полем ввода:
    ⛏ Кикнуть            — кикнуть игрока с сервера и сбросить его сессию
    🔐/🔓 2FA (переключатель) — 2FA по умолчанию выключена:
        выкл: при входе аккаунта бот шлёт только сообщение о входе;
        вкл:  при входе (после пароля) — inline «Войти / Отклонить».
    🔑 Сменить пароль    — новый пароль отправляется обычным сообщением
  клавиатура пересоздаётся по /menu и после каждого действия.
  Один Telegram = один аккаунт (кнопки действуют на него).

  2FA-вход (вкл): плагин создаёт login_request -> бот GET /api/pending ->
  inline «Войти / Отклонить» -> POST /api/resolve.
  Уведомления о входах (2FA выкл): сообщение + inline «⛏ Кикнуть».
"""
import asyncio
import html
import logging
import time

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

# id запросов входа, которым уже отправили кнопку (чтобы не дублировать)
_sent: set[int] = set()

# ---- reply-клавиатура («панель» внизу чата) ----
LABEL_KICK = "⛏ Кикнуть"
LABEL_2FA_ON = "🔐 Включить 2FA"
LABEL_2FA_OFF = "🔓 Выключить 2FA"
LABEL_PW = "🔑 Сменить пароль"
LABEL_SET = {LABEL_KICK, LABEL_2FA_ON, LABEL_2FA_OFF, LABEL_PW}

# Ожидание нового пароля (смена пароля кнопкой «Сменить пароль»):
# {tg_id: {"nickname": str, "until": epoch_sec}}
_pending_pw: dict[int, dict] = {}
PW_TTL_SEC = 300  # 5 минут на ввод пароля

# Коды ошибок смены пароля (плагин) -> человеческий текст
PW_ERR_TEXT = {
    "too_short": "слишком короткий",
    "too_long": "слишком длинный (больше 64 символов)",
    "like_nick": "не должен совпадать с ником аккаунта",
    "same_chars": "слишком простой (все символы одинаковые)",
    "player_not_found": "аккаунт не найден на сервере",
    "not_yours": "аккаунт больше не привязан к этому Telegram",
}

# Коды ошибок привязки (плагин) -> человеческий текст
LINK_ERR_TEXT = {
    "invalid_or_expired_code": "Код не найден или уже использован.\n"
                               "Запусти /addtg в игре ещё раз — придёт новый код.",
    "tg_already_linked": "У этого Telegram уже привязан аккаунт — к одному Telegram "
                         "можно привязать только один аккаунт.",
}


# ------------------------------------------------------------------ helpers

def esc(v) -> str:
    return html.escape(str(v), quote=False)


async def get_accounts_or_alert(chat_id: int, answer_cb=None) -> list | None:
    """Аккаунты пользователя. None, если сервер недоступен (алерт при наличии answer_cb)."""
    try:
        return await api.accounts(chat_id)
    except ApiError as e:
        log.warning("accounts error: %s", e)
        if answer_cb is not None:
            await answer_cb("⚠️ Сервер недоступен. Попробуй ещё раз.", show_alert=True)
        return None


def panel_text(accounts: list, result: str | None = None) -> str:
    lines = ["🎮 <b>Панель управления Elytrix</b>", ""]
    if not accounts:
        lines.append("К этому Telegram не привязан ни один аккаунт.")
        lines.append("")
        lines.append("Зайди на сервер, напиши в игре <code>/addtg</code> и отправь мне код: "
                     "<code>/link &lt;код&gt;</code>")
        lines.append("")
        lines.append("После привязки здесь появится статус аккаунта.")
    else:
        for a in accounts:
            where = "в игре" if a["online"] else "не в игре"
            twofa = "2FA вкл" if a["tg2fa"] else "2FA выкл"
            lines.append(f"• <b>{esc(a['nickname'])}</b> — {where} · {twofa}")
        lines.append("")
        if len(accounts) == 1:
            lines.append("Кнопки внизу чата — управление аккаунтом.")
        else:
            lines.append("Кнопки внизу чата управляют аккаунтами выше. "
                         "Новая привязка — один Telegram = один аккаунт.")
    if result:
        lines.append("")
        lines.append(result)
    return "\n".join(lines)


def reply_kb(accounts: list) -> ReplyKeyboardMarkup | None:
    """Клавиатура-«панель». None, если аккаунтов нет (клавиатуру надо убрать)."""
    if not accounts:
        return None
    # 1 TG = 1 аккаунт: кнопки действуют на единственный аккаунт.
    # (для старых записей с несколькими аккаунтами действия объяснят отдельно)
    toggle = LABEL_2FA_OFF if accounts[0]["tg2fa"] else LABEL_2FA_ON
    return ReplyKeyboardMarkup(
        keyboard=[
            [KeyboardButton(text=LABEL_KICK)],
            [KeyboardButton(text=toggle)],
            [KeyboardButton(text=LABEL_PW)],
        ],
        resize_keyboard=True,
        is_persistent=True,
        input_field_placeholder="Панель Elytrix",
    )


async def send_panel(chat_id: int, result: str | None = None) -> None:
    """Статус + клавиатура-«панель» (или её убрать, если аккаунтов нет)."""
    accounts = await get_accounts_or_alert(chat_id)
    if accounts is None:
        return
    text = panel_text(accounts, result)
    kb = reply_kb(accounts)
    if kb is None:
        await bot.send_message(chat_id, text, reply_markup=ReplyKeyboardRemove())
    else:
        await bot.send_message(chat_id, text, reply_markup=kb)


async def only_account(m: Message) -> dict | None:
    """Единственный аккаунт пользователя (reply-кнопки без выбора). None — уже ответили."""
    accounts = await get_accounts_or_alert(m.chat.id)
    if accounts is None:
        return None
    if not accounts:
        await m.answer("К этому Telegram не привязан ни один аккаунт.\n"
                       "Зайди на сервер, сделай <code>/addtg</code> и пришли код: "
                       "<code>/link &lt;код&gt;</code>")
        return None
    if len(accounts) > 1:
        await m.answer("У тебя привязано несколько аккаунтов (старая версия). "
                       "Сейчас действует правило «один Telegram — один аккаунт».\n"
                       "Напиши администратору, какой аккаунт оставить, — он снимет лишние.")
        return None
    return accounts[0]


def drop_pending_pw(tg_id: int) -> None:
    _pending_pw.pop(tg_id, None)


# ------------------------------------------------------------------ команды

@dp.message(CommandStart())
async def cmd_start(m: Message) -> None:
    if m.from_user is None:
        return
    drop_pending_pw(m.from_user.id)
    await m.answer(
        "👋 <b>Elytrix</b> — привязка Minecraft-аккаунта к Telegram.\n\n"
        "1) Зайди на сервер и напиши в игре <code>/addtg</code>\n"
        "2) Ты получишь код — отправь его мне: <code>/link &lt;код&gt;</code>\n\n"
        "После привязки внизу чата появятся кнопки панели: кик, 2FA и смена пароля. "
        "Они видны всегда — это и есть управление аккаунтом."
    )
    await send_panel(m.chat.id)


@dp.message(Command("help"))
async def cmd_help(m: Message) -> None:
    await cmd_start(m)


@dp.message(Command("menu"))
async def cmd_menu(m: Message) -> None:
    if m.from_user is not None:
        drop_pending_pw(m.from_user.id)
    await send_panel(m.chat.id)


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
    await m.answer(
        f"✅ Аккаунт <b>{esc(nickname)}</b> привязан к твоему Telegram!\n\n"
        "2FA по умолчанию выключена: при входе аккаунта я пришлю уведомление. "
        "Включить подтверждение входа можно кнопкой внизу.\n"
        "Если это не твой аккаунт — напиши администратору."
    )
    await send_panel(m.chat.id)


@dp.message(Command("unlink"))
async def cmd_unlink(m: Message) -> None:
    if m.from_user is not None:
        drop_pending_pw(m.from_user.id)
    await m.answer(
        "🔓 Полная отвязка аккаунта от Telegram пока делается на сервере.\n"
        "Напиши администратору ник — он снимет привязку вручную."
    )


# ------------------------------------------------------------------ reply-клавиатура (панель)

async def _kick_result(nick: str, data: dict) -> str:
    if data.get("ok"):
        if data.get("online"):
            return (f"✅ <b>{esc(nick)}</b> кикнут с сервера. "
                    f"Сессия сброшена — следующий вход будет по паролю.")
        return (f"⛏ <b>{esc(nick)}</b> сейчас не в игре. "
                f"Сессия сброшена — при следующем входе понадобится пароль.")
    err = data.get("error")
    if err in ("not_yours", "player_not_found"):
        return f"❌ <b>{esc(nick)}</b> больше не привязан к этому Telegram."
    log.warning("kick reply error: %s", err)
    return "⚠️ Не получилось. Попробуй ещё раз."


@dp.message(F.text == LABEL_KICK)
async def on_reply_kick(m: Message) -> None:
    if m.from_user is not None:
        drop_pending_pw(m.from_user.id)
    acc = await only_account(m)
    if acc is None:
        return
    nick = acc["nickname"]
    try:
        data = await api.kick(nick, m.from_user.id)
    except ApiError as e:
        log.warning("kick error: %s", e)
        await m.answer("⚠️ Сервер недоступен. Попробуй ещё раз.")
        return
    await send_panel(m.chat.id, await _kick_result(nick, data))


@dp.message(F.text.in_({LABEL_2FA_ON, LABEL_2FA_OFF}))
async def on_reply_2fa(m: Message) -> None:
    if m.from_user is None:
        return
    drop_pending_pw(m.from_user.id)
    acc = await only_account(m)
    if acc is None:
        return
    want_on = m.text == LABEL_2FA_ON
    nick = acc["nickname"]
    if acc["tg2fa"] == want_on:
        # состояние уже такое (клавиатура могла устареть) — просто показываем статус
        await send_panel(m.chat.id, f"ℹ️ 2FA для <b>{esc(nick)}</b> уже "
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
            await send_panel(m.chat.id, f"❌ <b>{esc(nick)}</b> больше не привязан "
                                        f"к этому Telegram.")
            return
        log.warning("toggle2fa error: %s", err)
        await m.answer("⚠️ Не получилось. Попробуй ещё раз.")
        return
    if data.get("tg2fa"):
        result = (f"🔐 2FA для <b>{esc(nick)}</b> включена. "
                  f"Теперь при входе (после пароля) нужно будет подтвердить его здесь.")
    else:
        result = (f"🔓 2FA для <b>{esc(nick)}</b> выключена. "
                  f"При входе буду присылать только уведомление.")
    await send_panel(m.chat.id, result)


@dp.message(F.text == LABEL_PW)
async def on_reply_change_password(m: Message) -> None:
    if m.from_user is None:
        return
    acc = await only_account(m)
    if acc is None:
        return
    nick = acc["nickname"]
    _pending_pw[m.from_user.id] = {"nickname": nick, "until": time.time() + PW_TTL_SEC}
    await m.answer(
        f"🔑 Отправь <b>новый пароль</b> для аккаунта <b>{esc(nick)}</b> одним сообщением.\n\n"
        "Пароль полностью заменит старый и понадобится при следующем входе в игру. "
        "Отменить ввод можно командой <code>/menu</code>."
    )


# Любое другое текстовое сообщение: если ждём пароль — это он
@dp.message(F.text & ~F.text.startswith("/") & ~F.text.in_(LABEL_SET))
async def on_password_text(m: Message) -> None:
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
        ok, err = await api.change_password(nick, m.from_user.id, password)
    except ApiError as e:
        log.warning("change password error: %s", e)
        await m.answer("⚠️ Сервер недоступен. Нажми «Сменить пароль» ещё раз.")
        drop_pending_pw(m.from_user.id)
        return
    if ok:
        drop_pending_pw(m.from_user.id)
        await m.answer(
            f"✅ Пароль аккаунта <b>{esc(nick)}</b> изменён.\n\n"
            "Старый пароль больше не действует, сессия сброшена — "
            "при следующем входе в игру используй новый пароль."
        )
        return
    if err in ("not_yours", "player_not_found"):
        drop_pending_pw(m.from_user.id)
        await m.answer(f"❌ Аккаунт <b>{esc(nick)}</b> больше не привязан к этому Telegram.\n"
                       f"Панель можно обновить командой <code>/menu</code>.")
        await send_panel(m.chat.id)
        return
    # ошибка правил пароля: код известен или нет — просим прислать другой
    reason = PW_ERR_TEXT.get(err, err or "не подходит")
    await m.answer(
        f"❌ Пароль для <b>{esc(nick)}</b> не принят ({reason}).\n"
        "Пришли другой пароль или отмени командой <code>/menu</code>."
    )


# ------------------------------------------------------------------ inline: кик из уведомления, входы 2FA

async def owned_account(cq: CallbackQuery) -> dict | None:
    """Аккаунт по uuid из inline-кнопки; проверяем, что он привязан к этому TG."""
    if cq.from_user is None:
        await cq.answer()
        return None
    uuid = cq.data.split(":", 1)[1]
    accounts = await get_accounts_or_alert(cq.message.chat.id, answer_cb=cq.answer)
    if accounts is None:
        return None
    for a in accounts:
        if a["uuid"] == uuid:
            return a
    await cq.answer("Аккаунт больше не привязан.", show_alert=True)
    return None


# Кик прямо из сообщения «Вход в аккаунт» (2FA выключена) — inline под уведомлением
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
        extra = ("Игрок кикнут, сессия сброшена."
                 if data.get("online")
                 else "Игрок сейчас не в игре, но сессия сброшена — вход будет по паролю.")
        try:
            await cq.message.edit_text(
                cq.message.html_text + f"\n\n✅ {extra}", reply_markup=None)
        except Exception:
            pass
    else:
        await cq.answer("❌ Не получилось кикнуть.", show_alert=True)


@dp.callback_query(F.data.startswith("la:"))
async def cb_approve(cq: CallbackQuery) -> None:
    await _answer_login(cq, "confirm")


@dp.callback_query(F.data.startswith("ld:"))
async def cb_deny(cq: CallbackQuery) -> None:
    await _answer_login(cq, "deny")


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
        await cq.message.edit_text("❌ <b>Вход отклонён.</b> Если это был не ты — смени пароль "
                                   "кнопкой внизу чата (<code>/menu</code>).",
                                   reply_markup=None)


@dp.callback_query()
async def cb_stale(cq: CallbackQuery) -> None:
    """Старые inline-панели прошлых версий: кнопки больше не нужны — панель внизу чата."""
    await cq.answer("Панель теперь внизу чата — нажми /menu", show_alert=False)


# ------------------------------------------------------------------ поллер

async def poller() -> None:
    """Опрос плагина: ожидающие 2FA-входы (inline «Войти/Отклонить») и уведомления о входах."""
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
                except Exception as e:  # бот заблокирован/диалог закрыт и т.п.
                    log.warning("send 2fa tg=%s req=%s: %s", row.get("tg_id"), req_id, e)
        except ApiError as e:
            log.warning("pending poll: %s", e)
        except Exception:
            log.exception("poller error")

        try:
            alerts = await api.alerts()
            for al in alerts:
                try:
                    text = str(al.get("text") or "")
                    uuid = al.get("player_uuid")
                    kb = None
                    if uuid:
                        # под уведомлением «Вход в аккаунт» — кнопка «Кикнуть»
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
    asyncio.create_task(poller())
    log.info("ElytrixBot запущен (API: %s)", CFG.API_BASE)
    await dp.start_polling(bot)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except (KeyboardInterrupt, SystemExit):
        log.info("Остановлен")
