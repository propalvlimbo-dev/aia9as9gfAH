"""ElytrixBot: Telegram-бот привязки аккаунтов Elytrix.

Бот общается с плагином ElytrixAuth по HTTP API (см. api.py):

  /addtg в игре -> код -> боту /link <код> -> POST /api/link
  после привязки бот показывает панель управления (инлайн-кнопки):
    • Кикнуть          — кикнуть игрока с сервера и сбросить его сессию
    • Включить/Выключить 2FA — 2FA по умолчанию выключена:
        выкл: при входе аккаунта бот шлёт только сообщение о входе;
        вкл:  при входе (после пароля) — кнопка «Войти / Отклонить».
    • Сменить пароль   — новый пароль отправляется сообщением боту
  панель пересоздаётся по /menu и обновляется после каждого действия.

  2FA-вход (вкл): плагин создаёт login_request -> бот GET /api/pending ->
  кнопка «Войти / Отклонить» -> POST /api/resolve.
"""
import asyncio
import html
import logging
import time

from aiogram import Bot, Dispatcher, F
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.filters import Command, CommandStart
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message

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

# Ожидание нового пароля (смена пароля из панели):
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

# chat_id, где закреплено сообщение-панель {tg_id: message_id}
_pin_msg: dict[int, int] = {}


# ------------------------------------------------------------------ helpers

def esc(v) -> str:
    return html.escape(str(v), quote=False)


async def get_accounts_or_alert(chat_id: int, answer_cb=None) -> list | None:
    """Аккаунты пользователя. None + алерт, если сервер недоступен."""
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
    else:
        lines.append("Аккаунты:")
        for a in accounts:
            where = "в игре" if a["online"] else "не в игре"
            twofa = "2FA вкл" if a["tg2fa"] else "2FA выкл"
            lines.append(f"• <b>{esc(a['nickname'])}</b> — {where} · {twofa}")
        lines.append("")
        if len(accounts) == 1:
            lines.append("Кнопки:")
        else:
            lines.append("Кнопки действуют на свой аккаунт:")
    if result:
        lines.append("")
        lines.append(result)
    return "\n".join(lines)


def panel_kb(accounts: list) -> InlineKeyboardMarkup | None:
    if not accounts:
        return None
    multi = len(accounts) > 1
    rows = []
    for a in accounts:
        nick = a["nickname"]
        tag = f" · {nick}" if multi else ""
        rows.append([InlineKeyboardButton(
            text="⛏ Кикнуть" + tag, callback_data=f"kick:{a['uuid']}")])
        toggle_text = ("🔓 Выключить 2FA" if a["tg2fa"] else "🔐 Включить 2FA") + tag
        rows.append([InlineKeyboardButton(text=toggle_text, callback_data=f"2fa:{a['uuid']}")])
        rows.append([InlineKeyboardButton(
            text="🔑 Сменить пароль" + tag, callback_data=f"pw:{a['uuid']}")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


async def update_or_pin_panel(chat_id: int) -> None:
    """Единая «панель»: пересоздаём/правим закреп.

    Панель должна быть видна всегда — держим её в закрепе чата:
      * если закреп уже наш — просто обновляем текст/кнопки,
      * иначе шлём новое сообщение, закрепляем (если получится) и запоминаем.
    Если закрепить не вышло (нет прав и т.п.) — остаётся обычным сообщением.
    """
    accounts = await get_accounts_or_alert(chat_id)
    if accounts is None:
        return
    text = panel_text(accounts)
    kb = panel_kb(accounts)
    pin_id = _pin_msg.get(chat_id)
    if pin_id is not None:
        try:
            await bot.edit_message_text(text, chat_id=chat_id, message_id=pin_id, reply_markup=kb)
            return
        except Exception as e:
            if "not modified" in str(e).lower():
                return  # текст/кнопки не изменились — панель на месте, дубли не нужны
            log.warning("panel edit pinned: %s", e)
            _pin_msg.pop(chat_id, None)
    try:
        sent = await bot.send_message(chat_id, text, reply_markup=kb)
    except Exception as e:
        log.warning("panel send: %s", e)
        return
    _pin_msg[chat_id] = sent.message_id
    try:
        await bot.pin_chat_message(chat_id, sent.message_id,
                                   disable_notification=True)
    except Exception as e:
        log.info("pin недоступен (%s) — панель остаётся сообщением", e)


async def owned_account(cq: CallbackQuery) -> dict | None:
    """Аккаунт по uuid из кнопки; проверяем, что он реально привязан к этому TG."""
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
        "После привязки я покажу панель управления (кик, 2FA, смена пароля) "
        "и закреплю её, чтобы кнопки всегда были под рукой."
    )
    await update_or_pin_panel(m.chat.id)


@dp.message(Command("help"))
async def cmd_help(m: Message) -> None:
    await cmd_start(m)


@dp.message(Command("menu"))
async def cmd_menu(m: Message) -> None:
    if m.from_user is not None:
        drop_pending_pw(m.from_user.id)
    await update_or_pin_panel(m.chat.id)


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
        "Включить подтверждение входа можно в панели управления.\n"
        "Если это не твой аккаунт — напиши администратору."
    )
    await update_or_pin_panel(m.chat.id)


@dp.message(Command("unlink"))
async def cmd_unlink(m: Message) -> None:
    if m.from_user is not None:
        drop_pending_pw(m.from_user.id)
    await m.answer(
        "🔓 Полная отвязка аккаунта от Telegram пока делается на сервере.\n"
        "Напиши администратору ник — он снимет привязку вручную."
    )


# ------------------------------------------------------------------ кнопки панели

async def _panel_edit_after(cq: CallbackQuery, result: str) -> None:
    """Обновляет панель после действия кнопки.

    Правим сообщение, на котором нажали (обычно это и есть закреп-панель);
    если оно не совпадает с закрепом — обновляем и закреп, чтобы «живая»
    панель всегда была свежей.
    """
    accounts = await get_accounts_or_alert(cq.message.chat.id)
    if accounts is None:
        return
    text = panel_text(accounts, result)
    kb = panel_kb(accounts)
    try:
        await cq.message.edit_text(text, reply_markup=kb)
    except Exception as e:
        log.warning("panel edit: %s", e)
    pin_id = _pin_msg.get(cq.message.chat.id)
    if pin_id is not None and pin_id != cq.message.message_id:
        try:
            await bot.edit_message_text(text, chat_id=cq.message.chat.id,
                                        message_id=pin_id, reply_markup=kb)
        except Exception as e:
            log.warning("panel edit pinned: %s", e)


async def _panel_action_error(cq: CallbackQuery, data: dict, nick: str) -> bool:
    """Общий разбор ok:false от действий панели. True — ошибку показали, дальше не идём."""
    if data.get("ok"):
        return False
    err = data.get("error")
    if err in ("not_yours", "player_not_found"):
        await _panel_edit_after(cq, f"❌ <b>{esc(nick)}</b> больше не привязан к этому Telegram.")
    else:
        log.warning("panel action error: %s", err)
        await cq.answer("⚠️ Не получилось. Попробуй ещё раз.", show_alert=True)
    return True


@dp.callback_query(F.data.startswith("kick:"))
async def cb_kick(cq: CallbackQuery) -> None:
    await cq.answer()
    acc = await owned_account(cq)
    if acc is None:
        return
    nick = acc["nickname"]
    try:
        data = await api.kick(nick, cq.from_user.id)
    except ApiError as e:
        log.warning("kick error: %s", e)
        await cq.answer("⚠️ Сервер недоступен. Попробуй ещё раз.", show_alert=True)
        return
    if await _panel_action_error(cq, data, nick):
        return
    if data.get("online"):
        result = (f"✅ <b>{esc(nick)}</b> кикнут с сервера. "
                  f"Сессия сброшена — следующий вход будет по паролю.")
    else:
        result = (f"⛏ <b>{esc(nick)}</b> сейчас не в игре. "
                  f"Сессия сброшена — при следующем входе понадобится пароль.")
    await _panel_edit_after(cq, result)


@dp.callback_query(F.data.startswith("2fa:"))
async def cb_toggle2fa(cq: CallbackQuery) -> None:
    await cq.answer()
    acc = await owned_account(cq)
    if acc is None:
        return
    nick = acc["nickname"]
    try:
        data = await api.toggle2fa(nick, cq.from_user.id)
    except ApiError as e:
        log.warning("toggle2fa error: %s", e)
        await cq.answer("⚠️ Сервер недоступен. Попробуй ещё раз.", show_alert=True)
        return
    if await _panel_action_error(cq, data, nick):
        return
    if data.get("tg2fa"):
        result = (f"🔐 2FA для <b>{esc(nick)}</b> включена. "
                  f"Теперь при входе (после пароля) нужно будет подтвердить его здесь.")
    else:
        result = (f"🔓 2FA для <b>{esc(nick)}</b> выключена. "
                  f"При входе буду присылать только уведомление.")
    await _panel_edit_after(cq, result)


# Кик прямо из сообщения «Вход в аккаунт» (2FA выключена) — кнопка под уведомлением
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


# ------------------------------------------------------------------ входы: 2FA и уведомления

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
                                   "в панели управления (<code>/menu</code>).",
                                   reply_markup=None)


async def poller() -> None:
    """Опрос плагина: ожидающие 2FA-входы (кнопки) и уведомления о входах."""
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
        {"command": "menu", "description": "Панель управления аккаунтами"},
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
