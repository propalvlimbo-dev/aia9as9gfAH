# ElytrixAuth + ElytrixBot

Авторизация для Minecraft-сети на прокси (**NullCordX / Waterfall / BungeeCord**)
с привязкой аккаунтов к Telegram и входом по паролю + подтверждением в Telegram (2FA).

```
Капча NullCordX → auth-сервер (маленькая карта) → /reg или /login
                                                   │
        аккаунт привязан к TG ──┘ пароль верный ──→ кнопка «Войти» в боте
                                                   │
                                                   ▼
                                            target-сервер (grief)
```

## Состав

| Часть | Путь | Что делает |
|---|---|---|
| Плагин | `elytrixauth-plugin/dist/ElytrixAuth-1.0.0.jar` | Java (Bungee API): команды `/reg /register /login /l /addtg`, удержание неавторизованных на auth, 2FA, защита от перебора |
| Бот | `elytrix-bot/` | Python (aiogram 3): `/link <код>`, `/unlink`, кнопки «Войти / Отклонить» при 2FA |
| БД | **встроена в плагин** (HSQLDB embedded) | файл БД в `plugins/ElytrixAuth/db/`, таблицы создаются сами при старте, ничего устанавливать не нужно |

Бот **не ходит в БД**: он общается с плагином по встроенному HTTP API
(бот «кидает запросы и получает ответы»), поэтому базу наружу открывать не нужно.

## 1. База данных — встроена, ничего делать не нужно

Плагин использует **HSQLDB** (полноценную SQL-БД) во встроенном режиме:
- файл БД — `plugins/ElytrixAuth/db/elytrix.*`;
- таблицы `players`, `pending_links`, `login_requests` создаются автоматически при первом запуске;
- **не нужно ставить MariaDB/MySQL/Postgres, заводить юзеров и пароли БД, открывать порты**.

## 2. Плагин (в plugins/ NullCordX)

1. Положить `elytrixauth-plugin/dist/ElytrixAuth-1.0.0.jar` в папку `plugins/` прокси.
2. Перезапустить NullCordX → создастся `plugins/ElytrixAuth/config.properties`
   (и в `db/` — файл встроенной БД).
3. Проверить в `config.properties`: `auth.server` (имя auth-сервера из config.yml прокси)
   и `target.server` (куда переводить после входа, у тебя `grief`). Пароли/БД настраивать **не нужно**.
4. Если `api.secret` пуст — он сгенерируется сам, значение напечатается в консоль
   (нужно для бота). Перезапустить прокси ещё раз.

Обязательные настройки прокси/бэкенда (уже сделаны у тебя):
- NullCordX: `online_mode: false`, `forwarding_mode` + совпадающий секрет;
- auth и все бэкенды: `server.properties → online-mode=false`,
  Paper: `velocity-support` или `spigot.yml → bungeecord: true` — **один режим**,
  совпадающий с `forwarding_mode` NullCordX;
- `priorities: [auth]`, `force_default_server: true` — все сначала попадают на auth.

### Команды

| Команда | Действие |
|---|---|
| `/reg <пароль> <пароль>` (`/register`) | регистрация (только на auth, пока не авторизован) |
| `/login <пароль>` (`/l`) | вход; если аккаунт привязан к TG — после пароля кнопка в боте |
| `/addtg` | получить код привязки Telegram (работает на любом сервере сети) |
| `/logout` | зарезервировано (пока не реализовано) |

### Как это защищено

- Пароли: **PBKDF2-HMAC-SHA256**, 210 000 итераций, соль 16 байт (не сырой SHA-256);
- лимит неверных `/login` (по IP и нику) → кик, чтобы не перебирали;
- таймер на авторизацию (по умолчанию 180 сек) → кик;
- неавторизованный не может сменить сервер (`/server`, реконнекты) — все дороги ведут на auth;
- чат и команды (кроме `/reg /login /addtg`) заблокированы до входа;
- код привязки одноразовый, 8 цифр, живёт 300 сек, проверка коллизий;
- 2FA-запрос живёт 90 сек, обрабатывается один раз.

## 3. Бот (Python, хостинг/Pterodactyl)

```bash
cd elytrix-bot
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env   # заполнить BOT_TOKEN, API_BASE, API_KEY
.venv/bin/python main.py
```

На Pterodactyl: egg `Python`, стартовая команда `python main.py`, переменные —
в разделе Environment: `BOT_TOKEN` (от @BotFather), `API_BASE=http://IP_ТВОЕГО_VDS:8754`
и `API_KEY` — **тот же, что `api.secret` в `config.properties` плагина**
(его печатает плагин в консоль при старте).

Внешний доступ к боту **не нужен**: он сам ходит в Telegram (long-polling) и к HTTP API плагина.
В firewall VDS открой порт `8754` хотя бы для IP хостинга бота:
```bash
ufw allow from <IP_ХОСТИНГА_БОТА> to any port 8754 proto tcp
```

### Пользовательский сценарий

1. Игрок в игре: `/addtg` → плагин показывает код.
2. Игрок в Telegram боту: `/link 12345678` → «Аккаунт привязан».
3. При следующем входе: `/login пароль` → бот присылает «Это ты?» с кнопками
   **✅ Войти / ❌ Отклонить** → нажал «Войти» → игрока пускает на `target.server`.
4. Отвязка: `/unlink` в боте.

## 4. Сборка плагина из исходников

```bash
cd elytrixauth-plugin
# hsqldb.jar (встроенная БД) уже в lib/. Если его нет — скачай HSQLDB
# (jar org.hsqldb:hsqldb) и положи в lib/hsqldb.jar.
./build.sh          # нужен JDK 17+; JAVAC=... ./build.sh, если javac не в PATH
# результат: dist/ElytrixAuth-1.0.0.jar (HSQLDB вшита внутрь)
```

Стабы Bungee API в `elytrixauth-plugin/stubs/` нужны только для компиляции
(в рантайме API даёт сам прокси), поэтому собирать можно без интернета.

## Известные ограничения v1

- Сессий «по IP» нет: каждый вход — заново `/login` (так безопаснее на пиратке).
- `/logout`, смена пароля, привязка VK/Discord/Google Authenticator — следующие версии.
- Бот не может подтверждать вход, если игрок не написал боту хотя бы раз
  (Telegram не даёт писать первым) — это штатное ограничение Telegram.
