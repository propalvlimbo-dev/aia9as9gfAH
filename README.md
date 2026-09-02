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
| БД | `sql/schema.sql` | MariaDB, общая для плагина и бота |

Плагин и бот **не знают друг о друге напрямую** — общаются только через таблицы
`players`, `pending_links`, `login_requests`.

## 1. База данных (на VPS с NullCordX)

**Самый простой способ — один скрипт** (ставит MariaDB, если нет, создаёт базу и юзера):

```bash
cd elytrixauth-plugin  # или любая папка с этим репозиторием
bash sql/setup_db.sh   # от root; спросит пароль
```

Либо вручную: создать базу и юзера (таблицы плагин создаст сам при старте):

```sql
CREATE DATABASE IF NOT EXISTS elytrix CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'elytrix'@'127.0.0.1' IDENTIFIED BY 'ПАРОЛЬ';
GRANT ALL PRIVILEGES ON elytrix.* TO 'elytrix'@'127.0.0.1';
FLUSH PRIVILEGES;
```

> Полная схема (3 таблицы) — в `sql/schema.sql`, но она **не обязательна**:
> `players`, `pending_links`, `login_requests` создаются автоматически.
> Время в БД — epoch-секунды (BIGINT), чтобы Java и Python не зависели от таймзон.

Юзера для бота (`elytrix_bot`, удалённый доступ с IP хостинга бота) создадим, когда дойдём до бота.

## 2. Плагин (в plugins/ NullCordX)

1. Положить `elytrixauth-plugin/dist/ElytrixAuth-1.0.0.jar` в папку `plugins/` прокси.
2. Перезапустить NullCordX → создастся `plugins/ElytrixAuth/config.properties`.
3. Заполнить в нём `db.*` (для плагина юзер `elytrix`@127.0.0.1),
   `auth.server` (имя auth-сервера из config.yml прокси) и `target.server`
   (куда переводить после входа, у тебя `grief`).
4. Перезапустить прокси ещё раз.

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
cp .env.example .env   # заполнить BOT_TOKEN и DB_*
.venv/bin/python main.py
```

На Pterodactyl: egg `Python`, стартовая команда `python main.py`, переменные —
в разделе Environment (токен @BotFather, параметры БД). Внешний доступ к боту **не нужен**:
он сам ходит в Telegram (long-polling) и к MariaDB.

### Пользовательский сценарий

1. Игрок в игре: `/addtg` → плагин показывает код.
2. Игрок в Telegram боту: `/link 12345678` → «Аккаунт привязан».
3. При следующем входе: `/login пароль` → бот присылает «Это ты?» с кнопками
   **✅ Войти / ❌ Отклонить** → нажал «Войти» → игрока пускает на `target.server`.
4. Отвязка: `/unlink` в боте.

## 4. Сборка плагина из исходников

```bash
cd elytrixauth-plugin
# положить lib/mariadb-java-client.jar (см. build.sh) и выполнить:
./build.sh          # нужен JDK 17+; JAVAC=... ./build.sh, если javac не в PATH
# результат: dist/ElytrixAuth-1.0.0.jar
```

Стабы Bungee API в `elytrixauth-plugin/stubs/` нужны только для компиляции
(в рантайме API даёт сам прокси), поэтому собирать можно без интернета.

## Известные ограничения v1

- Сессий «по IP» нет: каждый вход — заново `/login` (так безопаснее на пиратке).
- `/logout`, смена пароля, привязка VK/Discord/Google Authenticator — следующие версии.
- Бот не может подтверждать вход, если игрок не написал боту хотя бы раз
  (Telegram не даёт писать первым) — это штатное ограничение Telegram.
