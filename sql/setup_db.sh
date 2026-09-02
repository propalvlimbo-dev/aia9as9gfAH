#!/usr/bin/env bash
# ============================================================
# Быстрая настройка MariaDB под ElytrixAuth.
# Запускать НА VPS (где NullCordX), от root:
#     bash setup_db.sh
# Скрипт: ставит MariaDB (если нет), заводит базу elytrix и
# юзера elytrix (localhost). Таблицы плагин создаст сам.
# ============================================================
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "Запусти от root:  sudo bash setup_db.sh"
    exit 1
fi

# 1) ставим MariaDB, если её нет
if ! command -v mysql >/dev/null 2>&1; then
    echo ">>> Устанавливаю MariaDB..."
    apt-get update -y
    apt-get install -y mariadb-server
fi

# 2) поднимаем сервис
systemctl enable --now mariadb >/dev/null 2>&1 || service mariadb start >/dev/null 2>&1 || true

# 3) спрашиваем пароль
echo "Придумай пароль для БД (юзер elytrix). Он понадобится в config.properties плагина."
read -r -s -p "Пароль: " PASS; echo
if [ -z "$PASS" ]; then
    echo "Пароль пустой — отменяю."
    exit 1
fi
# защита от спецсимволов в одинарных кавычках
PASS_ESC=$(printf '%s' "$PASS" | sed "s/'/''/g")

# 4) создаём базу и юзера (повторный запуск тоже безопасен)
mysql <<SQL
CREATE DATABASE IF NOT EXISTS elytrix CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'elytrix'@'127.0.0.1' IDENTIFIED BY '$PASS_ESC';
ALTER USER 'elytrix'@'127.0.0.1' IDENTIFIED BY '$PASS_ESC';
GRANT ALL PRIVILEGES ON elytrix.* TO 'elytrix'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

echo ""
echo "Готово!"
echo "  База:   elytrix"
echo "  Юзер:   elytrix @ 127.0.0.1"
echo "  Пароль: $PASS"
echo ""
echo "Дальше в config.properties плагина (plugins/ElytrixAuth/) укажи:"
echo "  db.password=$PASS"
echo "и перезапусти NullCordX — таблицы создадутся сами."
