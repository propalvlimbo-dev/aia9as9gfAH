-- ============================================================
-- ElytrixAuth | Схема БД (справочно)
--
-- ВАЖНО: плагин создаёт таблицы САМ при первом запуске.
-- Проще всего настроить БД скриптом (от root на VPS):
--     bash sql/setup_db.sh
-- Он поставит MariaDB (если нет), создаст базу elytrix и юзера.
--
-- Ниже — схема для ручной установки / справки.
-- ============================================================

CREATE DATABASE IF NOT EXISTS elytrix
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
USE elytrix;

-- ------------------------------------------------------------
-- Игроки (аккаунты авторизации)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS players (
  uuid          CHAR(36)     NOT NULL COMMENT 'UUID игрока (offline, от прокси)',
  nickname      VARCHAR(16)  NOT NULL COMMENT 'Ник игрока',
  password_hash VARCHAR(255) DEFAULT NULL COMMENT 'PBKDF2: pbkdf2_sha256$iter$salt$hash',
  tg_id         BIGINT       DEFAULT NULL COMMENT 'Telegram user id (привязка, NULL = нет)',
  reg_ip        VARCHAR(45)  DEFAULT NULL,
  reg_ts        BIGINT       NOT NULL COMMENT 'epoch seconds',
  last_ip       VARCHAR(45)  DEFAULT NULL,
  last_login_ts BIGINT       DEFAULT NULL COMMENT 'epoch seconds',
  PRIMARY KEY (uuid),
  UNIQUE KEY uq_players_nickname (nickname),
  KEY idx_players_tg (tg_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- Коды привязки Telegram:
--   плагин: INSERT (status='open') по команде /addtg
--   бот:    /link <code> -> UPDATE status='bound' + players.tg_id
--   плагин: опрос -> если 'bound', сообщить игроку об успехе
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pending_links (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_uuid CHAR(36)    NOT NULL,
  code        VARCHAR(10) NOT NULL COMMENT 'одноразовый код из игры',
  status      ENUM('open','bound','expired') NOT NULL DEFAULT 'open',
  created_ts  BIGINT      NOT NULL COMMENT 'epoch seconds',
  expires_ts  BIGINT      NOT NULL COMMENT 'epoch seconds',
  KEY idx_links_status (status, expires_ts),
  KEY idx_links_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- Запросы входа (2FA через бота):
--   плагин: пароль верный + есть tg_id -> INSERT (status='pending')
--   бот:    опрос pending -> шлёт кнопку «Войти / Отклонить»,
--           по нажатию UPDATE status='confirmed' | 'denied'
--   плагин: опрос -> confirmed: пустить; denied: кик; expired: кик
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS login_requests (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_uuid CHAR(36)     NOT NULL,
  nickname    VARCHAR(16)  NOT NULL COMMENT 'денормализация: чтобы бот не делал join',
  ip          VARCHAR(45)  DEFAULT NULL,
  status      ENUM('pending','notified','confirmed','denied','expired') NOT NULL DEFAULT 'pending',
  created_ts  BIGINT       NOT NULL COMMENT 'epoch seconds',
  expires_ts  BIGINT       NOT NULL COMMENT 'epoch seconds',
  KEY idx_requests_status (status, expires_ts),
  KEY idx_requests_player (player_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Пользователи БД (создать один раз вручную):
--
--   Плагин (на VPS, localhost):
--     CREATE USER 'elytrix'@'127.0.0.1' IDENTIFIED BY 'СИЛЬНЫЙ_ПАРОЛЬ_1';
--     GRANT SELECT,INSERT,UPDATE,DELETE ON elytrix.* TO 'elytrix'@'127.0.0.1';
--
--   Бот (удалённо, IP хостинга бота вместо IP_БОТА):
--     CREATE USER 'elytrix_bot'@'IP_БОТА' IDENTIFIED BY 'СИЛЬНЫЙ_ПАРОЛЬ_2';
--     GRANT SELECT,INSERT,UPDATE ON elytrix.* TO 'elytrix_bot'@'IP_БОТА';
--     FLUSH PRIVILEGES;
--
--   MariaDB на VPS должна слушать сеть: /etc/mysql/mariadb.conf.d/50-server.cnf
--     bind-address = 0.0.0.0
--   и в firewall открыть TCP 3306 ТОЛЬКО для IP хостинга бота.
-- ============================================================
