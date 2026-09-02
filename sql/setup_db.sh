#!/usr/bin/env bash
# ============================================================
# Одна команда: ставит MariaDB (если нет), создаёт базу elytrix,
# юзера elytrix@127.0.0.1 и САМ прописывает пароль и api.secret
# в config.properties плагина ElytrixAuth.
#
# Запуск (от root, в папке NullCordX):
#     bash sql/setup_db.sh [путь_к_plugins]
# Например: bash sql/setup_db.sh /home/mcserver/plugins
# Если путь не указан — ищет ./plugins/ElytrixAuth рядом.
# ============================================================
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "Запусти от root:  sudo bash sql/setup_db.sh"
    exit 1
fi

# --- где лежит плагин ---
if [ -n "$1" ]; then
    PLUGIN_DIR="$1/ElytrixAuth"
elif [ -d "./plugins/ElytrixAuth" ]; then
    PLUGIN_DIR="$(pwd)/plugins/ElytrixAuth"
else
    # попробуем найти папку плагина рядом (первый подходящий путь)
    CANDIDATES=("$HOME" /root /home /opt /srv)
    PLUGIN_DIR=""
    for base in "${CANDIDATES[@]}"; do
        FOUND=$(find "$base" -maxdepth 4 -type d -name ElytrixAuth 2>/dev/null | head -1)
        if [ -n "$FOUND" ]; then
            PLUGIN_DIR="$FOUND"
            break
        fi
    done
    if [ -z "$PLUGIN_DIR" ]; then
        echo "Не нашёл папку plugins/ElytrixAuth."
        echo "Положи jar плагина в plugins/, запусти NullCordX один раз (чтобы создался config.properties),"
        echo "и повтори скрипт, либо укажи путь: bash setup_db.sh /путь/до/plugins"
        exit 1
    fi
fi
echo ">>> Папка плагина: $PLUGIN_DIR"

# --- 1) MariaDB ---
if ! command -v mysql >/dev/null 2>&1; then
    echo ">>> Устанавливаю MariaDB..."
    apt-get update -y
    DEBIAN_FRONTEND=noninteractive apt-get install -y mariadb-server
fi
systemctl enable --now mariadb >/dev/null 2>&1 || service mariadb start >/dev/null 2>&1 || true

# --- 2) config.properties: пароль БД и api.secret ---
CONFIG="$PLUGIN_DIR/config.properties"
mkdir -p "$PLUGIN_DIR"

DB_PASS=""
API_SECRET=""
if [ -f "$CONFIG" ]; then
    # читаем текущие значения
    DB_PASS=$(grep -E "^db\.password=" "$CONFIG" | head -1 | cut -d= -f2- | tr -d ' ')
    API_SECRET=$(grep -E "^api\.secret=" "$CONFIG" | head -1 | cut -d= -f2- | tr -d ' ')
fi
[ -z "$DB_PASS" ] || [ "$DB_PASS" = "CHANGE_ME" ] && DB_PASS=$(openssl rand -hex 12)
[ -z "$API_SECRET" ] || [ "$API_SECRET" = "CHANGE_ME" ] && API_SECRET=$(openssl rand -hex 24)

if [ ! -f "$CONFIG" ]; then
    echo ">>> Создаю config.properties со стандартными настройками..."
    cat > "$CONFIG" <<EOF
db.host=127.0.0.1
db.port=3306
db.name=elytrix
db.user=elytrix
db.password=$DB_PASS
db.pool.size=4
auth.server=auth
target.server=grief
login.timeout.seconds=180
max.failed.tries=5
failed.window.seconds=60
min.password.length=4
link.code.ttl.seconds=300
login2fa.ttl.seconds=90
api.port=8754
api.secret=$API_SECRET
api.bind=0.0.0.0
EOF
else
    sed -i "s|^db\.password=.*|db.password=$DB_PASS|" "$CONFIG"
    sed -i "s|^api\.secret=.*|api.secret=$API_SECRET|" "$CONFIG"
fi
chmod 600 "$CONFIG"

# --- 3) создаём базу и юзера ---
echo ">>> Создаю базу elytrix и юзера..."
# root: сначала пробуем без пароля (unix_socket у свежей MariaDB)
if mysql -e "SELECT 1" >/dev/null 2>&1; then
    MYSQL=(mysql)
else
    read -r -s -p "Пароль root от MariaDB: " ROOTPASS; echo
    MYSQL=(mysql -uroot -p"$ROOTPASS")
fi
"${MYSQL[@]}" <<SQL
CREATE DATABASE IF NOT EXISTS elytrix CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'elytrix'@'127.0.0.1' IDENTIFIED BY '$DB_PASS';
ALTER USER 'elytrix'@'127.0.0.1' IDENTIFIED BY '$DB_PASS';
GRANT ALL PRIVILEGES ON elytrix.* TO 'elytrix'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

echo ""
echo "======================================================"
echo " Готово!"
echo "  config.properties: $CONFIG"
echo "  База:   elytrix | юзер: elytrix@127.0.0.1"
echo ""
echo " Дальше:"
echo "  1) Положи ElytrixAuth-1.0.0.jar в plugins/ NullCordX"
echo "  2) Перезапусти NullCordX — таблицы создадутся сами"
echo "  3) Для бота скопируй в .env бота:"
echo "       API_BASE=http://IP_ЭТОГО_VDS:8754"
echo "       API_KEY=$API_SECRET"
echo "     и открой порт 8754 в firewall VDS (хотя бы для IP хостинга бота):"
echo "       ufw allow from <IP_БОТА> to any port 8754 proto tcp"
echo "======================================================"
