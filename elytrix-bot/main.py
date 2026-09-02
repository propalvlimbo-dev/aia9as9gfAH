"""ElytrixBot: Telegram-бот привязки аккаунтов и подтверждения входа (2FA).

Схема:
  /addtg в игре -> код -> боту /link <код> -> привязка (players.tg_id)
  /login пароль на сервере -> login_requests(pending) -> бот шлёт кнопку ->
  нажатие -> login_requests(confirmed) -> плагин пускает игрока
"""
import asyncio
import logging

from aiogram import Bot, Dispatcher, F
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.filters import Command, CommandStart
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message

from config import Config
from db import Db

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s: %(message)s")
log = logging.getLogger("elytrix")

CFG = Config()
problems = CFG.validate()
if problems:
    raise SystemExit("Конфигурация неполная:\n  - " + "\n  - ".join(problems))

db = Db(CFG.DB_HOST, CFG.DB_PORT, CFG.DB_USER, CFG.DB_PASSWORD, CFG.DB_NAME)
bot = Bot(token=CFG.BOT_TOKEN, default=DefaultBotProperties(parse_mode=ParseMode.HTML))
dp = Dispatcher()


# ------------------------------------------------------------------ команды

@dp.message(CommandStart())
async def cmd_start(m: Message) -> None:
    await m.answer(
        "👋 <b>Elytrix</b> — привязка Minecraft-аккаунта к Telegram.\n\n"
        "1) Зайди на сервер и напиши в игре <code>/addtg</code>\n"
        "2) Ты получишь код — отправь его мне: <code>/link &lt;код&gt;</code>\n\n"
        "Когда привязанный аккаунт входит на сервер, после пароля "
        "я пришлю кнопку подтверждения входа."
    )


@dp.message(Command("help"))
async def cmd_help(m: Message) -> None:
    await cmd_start(m)


@dp.message(Command("link"))
async def cmd_link(m: Message) -> None:
    args = m.text.split()
    if len(args) != 2 or not args[1].isdigit():
        await m.answer("Отправь код из игры так: <code>/link 12345678</code>")
        return
    code = args[1]
    if m.from_user is None:
        return
    tg_id = m.from_user.id

    link = await db.find_open_link(code)
    if link is None:
        await m.answer("❌ Код не найден или истёк.\n"
                       "Запусти <code>/addtg</code> в игре ещё раз — придёт новый код.")
        return

    nickname = await db.bind_player(link.id, link.player_uuid, tg_id)
    if nickname is None:
        await m.answer("❌ Код уже использован. Запусти <code>/addtg</code> в игре заново.")
        return

    accounts = await db.linked_accounts(tg_id)
    await m.answer(
        f"✅ Аккаунт <b>{nickname}</b> привязан к твоему Telegram!\n\n"
        f"Теперь при входе (после пароля) нужно будет нажать «Войти» в этом чате.\n"
        f"Привязано аккаунтов: {len(accounts)}. Отвязать: <code>/unlink</code>"
    )


@dp.message(Command("unlink"))
async def cmd_unlink(m: Message) -> None:
    if m.from_user is None:
        return
    tg_id = m.from_user.id
    accounts = await db.linked_accounts(tg_id)
    if not accounts:
        await m.answer("У тебя нет привязанных аккаунтов.")
        return
    await db.unbind_tg(tg_id)
    nicks = ", ".join(a["nickname"] for a in accounts)
    await m.answer(f"🔓 Отвязал: <b>{nicks}</b>. Коды входа больше не приходят.")


# ------------------------------------------------------------------ кнопки 2FA

@dp.callback_query(F.data.startswith("la:"))
async def cb_approve(cq: CallbackQuery) -> None:
    await _answer_login(cq, confirmed=True)


@dp.callback_query(F.data.startswith("ld:"))
async def cb_deny(cq: CallbackQuery) -> None:
    await _answer_login(cq, confirmed=False)


async def _answer_login(cq: CallbackQuery, confirmed: bool) -> None:
    req_id = int(cq.data.split(":", 1)[1])
    status = "confirmed" if confirmed else "denied"
    updated = await db.settle_login(req_id, status)
    if updated != 1:
        await cq.answer("⏰ Запрос устарел или уже обработан.", show_alert=False)
        try:
            await cq.message.edit_text(cq.message.html_text + "\n\n<i>(устарел)</i>",
                                       reply_markup=None)
        except Exception:
            pass
        return
    if confirmed:
        await cq.answer("✅ Вход подтверждён!", show_alert=False)
        await cq.message.edit_text(
            "✅ <b>Вход подтверждён.</b> Игрок будет пущен на сервер.",
            reply_markup=None)
    else:
        await cq.answer("❌ Вход отклонён.", show_alert=False)
        await cq.message.edit_text(
            "❌ <b>Вход отклонён.</b> Если это был не ты — смени пароль на сервере.",
            reply_markup=None)


# ------------------------------------------------------------------ поллер 2FA

async def login_poller() -> None:
    log.info("Poller 2FA запущен (интервал %.1f c)", CFG.POLL_INTERVAL)
    while True:
        try:
            rows = await db.fetch_pending_logins()
            for row in rows:
                tg_id = await db.tg_id_by_uuid(row.player_uuid)
                if tg_id is None:
                    await db.settle_login(row.id, "expired")
                    continue
                kb = InlineKeyboardMarkup(inline_keyboard=[[
                    InlineKeyboardButton(text="✅ Войти", callback_data=f"la:{row.id}"),
                    InlineKeyboardButton(text="❌ Отклонить", callback_data=f"ld:{row.id}"),
                ]])
                ip_line = f"IP: <code>{row.ip}</code>\n" if row.ip else ""
                try:
                    await bot.send_message(
                        tg_id,
                        "🔐 <b>Запрос входа на сервер</b>\n\n"
                        f"Игрок: <b>{row.nickname}</b>\n{ip_line}"
                        f"Это ты?",
                        reply_markup=kb)
                    await db.mark_notified(row.id)
                except Exception as e:  # бот заблокирован/удалён и т.п.
                    log.warning("send 2fa tg=%s req=%s: %s", tg_id, row.id, e)
        except Exception as e:
            log.exception("poller error: %s", e)
        await asyncio.sleep(CFG.POLL_INTERVAL)


# ------------------------------------------------------------------ main

async def main() -> None:
    await bot.set_my_commands([
        {"command": "link", "description": "Привязать аккаунт по коду из игры (/addtg)"},
        {"command": "unlink", "description": "Отвязать аккаунт"},
        {"command": "help", "description": "Помощь"},
    ])
    asyncio.create_task(login_poller())
    log.info("ElytrixBot запущен")
    await dp.start_polling(bot)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except (KeyboardInterrupt, SystemExit):
        log.info("Остановлен")
