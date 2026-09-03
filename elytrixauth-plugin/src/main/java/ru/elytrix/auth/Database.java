package ru.elytrix.auth;

import java.io.File;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Хранилище аккаунтов: HSQLDB, встроенная (embedded) прямо в плагин.
 * Никакого отдельного сервера БД не нужно — файл БД лежит в папке плагина
 * (plugins/ElytrixAuth/db/elytrix.*), таблицы создаются автоматически
 * при первом запуске.
 */
public final class Database {

    /** Строка игрока из таблицы players. */
    public static final class PlayerRow {
        public final UUID uuid;
        public final String nickname;
        public final String passwordHash; // может быть null
        public final Long tgId;           // может быть null
        public final String sessionIp;    // может быть null (сессия не выдана)
        public final Long sessionExpires; // может быть null

        PlayerRow(UUID uuid, String nickname, String passwordHash, Long tgId,
                  String sessionIp, Long sessionExpires) {
            this.uuid = uuid;
            this.nickname = nickname;
            this.passwordHash = passwordHash;
            this.tgId = tgId;
            this.sessionIp = sessionIp;
            this.sessionExpires = sessionExpires;
        }
    }

    /** Запись для бота: ожидающий 2FA-запрос входа. */
    public static final class PendingRequest {
        public final long id;
        public final String playerUuid;
        public final String nickname;
        public final String ip;
        public final long tgId;

        PendingRequest(long id, String playerUuid, String nickname, String ip, long tgId) {
            this.id = id;
            this.playerUuid = playerUuid;
            this.nickname = nickname;
            this.ip = ip;
            this.tgId = tgId;
        }
    }

    private static final int MAX_POOL = 4;

    private final String url;
    private final Logger log;

    private final Deque<Connection> idle = new ArrayDeque<>();
    private int open;

    public Database(File dataFolder, PluginConfig cfg, Logger log) throws SQLException {
        this.log = log;
        // путь к файлу БД настраивается в config.properties (db.file),
        // по умолчанию — db/elytrix внутри папки плагина
        File dbFile = resolveDbFile(dataFolder, cfg.dbFile());
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Не удалось создать папку БД: " + parent);
        }
        // HSQLDB логирует служебные INFO (dataFileCache open, checkpointClose и т.п.)
        // через org.hsqldb.lib.FrameworkLogger: по умолчанию — напрямую в System.err,
        // в прокси-средах (Bungee/NullCordX) — в JUL-логгеры вида hsqldb.db.*.ENGINE.
        // Ни то, ни другое в консоли прокси не нужно. Безопасно глушим изнутри:
        //  1) hsqldb.reconfig_logging=false — чтобы <clinit> FrameworkLogger НЕ вызывал
        //     LogManager.reset()/readConfiguration() (это снесло бы JUL-настройки прокси);
        //  2) статический флаг noopMode=true — FrameworkLogger.privlog() при noopMode
        //     молча возвращается, т.е. HSQLDB вообще ничего не печатает.
        // System.out/System.err и JUL-логгеры прокси при этом не трогаем.
        try {
            System.setProperty("hsqldb.reconfig_logging", "false");
            Class<?> frameworkLogger = Class.forName("org.hsqldb.lib.FrameworkLogger");
            java.lang.reflect.Field noop = frameworkLogger.getDeclaredField("noopMode");
            noop.setAccessible(true);
            noop.setBoolean(null, true);
        } catch (Throwable ignored) {
            // если рефлексия не удалась — HSQLDB просто будет писать свои INFO, это не критично
        }
        this.url = "jdbc:hsqldb:file:" + dbFile.getAbsolutePath()
                + ";hsqldb.lock_file=false;hsqldb.default_table_type=cached;sql.syntax_mys=true";
        try {
            Class.forName("org.hsqldb.jdbc.JDBCDriver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("HSQLDB driver не вшит в jar плагина", e);
        }
        // проверка соединения + автосоздание таблиц
        try (Connection c = DriverManager.getConnection(url)) {
            ensureSchema(c);
        }
    }

    /** Относительный db.file считается от папки плагина; абсолютный — как есть. */
    private static File resolveDbFile(File dataFolder, String dbFile) {
        File f = new File(dbFile);
        return f.isAbsolute() ? f : new File(dataFolder, dbFile);
    }

    /** Таблицы создаются сами при старте; повторные запуски безопасны. */
    private void ensureSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS players ("
                    + " uuid VARCHAR(36) PRIMARY KEY,"
                    + " nickname VARCHAR(16) NOT NULL UNIQUE,"
                    + " password_hash VARCHAR(255),"
                    + " tg_id BIGINT,"
                    + " reg_ip VARCHAR(45),"
                    + " reg_ts BIGINT NOT NULL,"
                    + " last_ip VARCHAR(45),"
                    + " last_login_ts BIGINT,"
                    + " session_ip VARCHAR(45),"
                    + " session_expires BIGINT"
                    + ")");
            st.execute("CREATE TABLE IF NOT EXISTS pending_links ("
                    + " id BIGINT IDENTITY PRIMARY KEY,"
                    + " player_uuid VARCHAR(36) NOT NULL,"
                    + " code VARCHAR(10) NOT NULL,"
                    + " status VARCHAR(16) NOT NULL,"
                    + " created_ts BIGINT NOT NULL,"
                    + " expires_ts BIGINT NOT NULL"
                    + ")");
            st.execute("CREATE TABLE IF NOT EXISTS login_requests ("
                    + " id BIGINT IDENTITY PRIMARY KEY,"
                    + " player_uuid VARCHAR(36) NOT NULL,"
                    + " nickname VARCHAR(16) NOT NULL,"
                    + " ip VARCHAR(45),"
                    + " status VARCHAR(16) NOT NULL,"
                    + " created_ts BIGINT NOT NULL,"
                    + " expires_ts BIGINT NOT NULL"
                    + ")");
            st.execute("CREATE TABLE IF NOT EXISTS ip_bans ("
                    + " ip VARCHAR(45) PRIMARY KEY,"
                    + " banned_until BIGINT NOT NULL"
                    + ")");
        }
        // индексы (HSQLDB не понимает IF NOT EXISTS — проверяем через системные таблицы)
        createIndexIfMissing(c, "players", "idx_players_tg", "CREATE INDEX idx_players_tg ON players(tg_id)");
        createIndexIfMissing(c, "pending_links", "idx_links_code", "CREATE INDEX idx_links_code ON pending_links(code)");
        createIndexIfMissing(c, "pending_links", "idx_links_status", "CREATE INDEX idx_links_status ON pending_links(status, expires_ts)");
        createIndexIfMissing(c, "login_requests", "idx_requests_status", "CREATE INDEX idx_requests_status ON login_requests(status, expires_ts)");
        createIndexIfMissing(c, "login_requests", "idx_requests_player", "CREATE INDEX idx_requests_player ON login_requests(player_uuid)");
        // миграция для старых БД: колонки сессий могли не создаться
        ensureColumn(c, "PLAYERS", "SESSION_IP", "ALTER TABLE players ADD COLUMN session_ip VARCHAR(45)");
        ensureColumn(c, "PLAYERS", "SESSION_EXPIRES", "ALTER TABLE players ADD COLUMN session_expires BIGINT");
    }

    /** Добавляет колонку, если её ещё нет (HSQLDB 2.3 не умеет ADD COLUMN IF NOT EXISTS). */
    private void ensureColumn(Connection c, String table, String column, String alterSql) {
        String existsSql = "SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = c.prepareStatement(existsSql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return; // колонка уже есть
                }
            }
        } catch (SQLException ignored) {
            return; // если схему не прочитать — не рискуем
        }
        try (Statement st = c.createStatement()) {
            st.execute(alterSql);
        } catch (SQLException e) {
            log.log(Level.FINE, "ensureColumn " + column + ": " + e.getMessage());
        }
    }

    private void createIndexIfMissing(Connection c, String table, String name, String ddl) {
        // System-index-запросы HSQLDB: INFORMATION_SCHEMA.SYSTEM_INDEXINFO (table_name, index_name)
        String existsSql = "SELECT 1 FROM INFORMATION_SCHEMA.SYSTEM_INDEXINFO "
                + "WHERE TABLE_NAME = ? AND INDEX_NAME = ?";
        try (PreparedStatement ps = c.prepareStatement(existsSql)) {
            ps.setString(1, table.toUpperCase(java.util.Locale.ROOT));
            ps.setString(2, name.toUpperCase(java.util.Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return; // уже есть
                }
            }
        } catch (SQLException e) {
            // если запрос к системной таблице не сработал — пытаемся создать вслепую
        }
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        } catch (SQLException e) {
            log.log(Level.FINE, "Не удалось создать индекс " + name + ": " + e.getMessage());
        }
    }

    private Connection newConnection() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private synchronized Connection acquire() throws SQLException {
        for (int i = 0; i < 100; i++) {
            Connection c = idle.poll();
            if (c != null) {
                if (c.isValid(2)) {
                    return c;
                }
                closeQuiet(c);
                open--;
                continue;
            }
            if (open < MAX_POOL) {
                open++;
                return newConnection();
            }
            try {
                wait(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Прервано ожидание соединения", e);
            }
        }
        throw new SQLException("Пул соединений исчерпан");
    }

    private synchronized void release(Connection c) {
        if (c != null) {
            idle.push(c);
            notifyAll();
        }
    }

    private synchronized void discard(Connection c) {
        if (c != null) {
            closeQuiet(c);
            open--;
            notifyAll();
        }
    }

    private void closeQuiet(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
        }
    }

    public synchronized void close() {
        Connection c;
        while ((c = idle.poll()) != null) {
            closeQuiet(c);
        }
        open = 0;
        // чистый checkpoint + компактизация: при следующем старте папка БД чистая
        try (Connection sc = DriverManager.getConnection(url)) {
            try (Statement st = sc.createStatement()) {
                st.execute("SHUTDOWN COMPACT");
            }
        } catch (SQLException e) {
            log.log(Level.FINE, "shutdown db: " + e.getMessage());
        }
    }

    private <T> T withConn(SqlWork<T> work) throws SQLException {
        Connection c = acquire();
        try {
            return work.run(c);
        } catch (SQLException e) {
            discard(c);
            c = null;
            Connection c2 = acquire();
            try {
                return work.run(c2);
            } finally {
                release(c2);
            }
        } finally {
            if (c != null) {
                release(c);
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    // ---------------- players ----------------

    private PlayerRow row(ResultSet rs) throws SQLException {
        Long tg = rs.getObject("tg_id") == null ? null : rs.getLong("tg_id");
        String sessionIp = rs.getString("session_ip");
        Long sessionExpires = rs.getObject("session_expires") == null ? null : rs.getLong("session_expires");
        return new PlayerRow(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("nickname"),
                rs.getString("password_hash"),
                tg,
                sessionIp,
                sessionExpires);
    }

    public Optional<PlayerRow> findPlayer(String nickname) {
        try {
            return withConn(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, nickname, password_hash, tg_id, session_ip, session_expires "
                                + "FROM players WHERE nickname = ?")) {
                    ps.setString(1, nickname);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(row(rs)) : Optional.empty();
                    }
                }
            });
        } catch (SQLException e) {
            log.log(Level.WARNING, "findPlayer error", e);
            return Optional.empty();
        }
    }

    public void createPlayer(UUID uuid, String nickname, String passwordHash, String ip, long now) throws SQLException {
        withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO players (uuid, nickname, password_hash, reg_ip, reg_ts, last_ip, last_login_ts) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, nickname);
                ps.setString(3, passwordHash);
                ps.setString(4, ip);
                ps.setLong(5, now);
                ps.setString(6, ip);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Регистронезависимый поиск игрока (для админ-команд). */
    public Optional<PlayerRow> findPlayerCi(String nickname) {
        try {
            return withConn(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, nickname, password_hash, tg_id, session_ip, session_expires "
                                + "FROM players WHERE LOWER(nickname) = LOWER(?)")) {
                    ps.setString(1, nickname);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(row(rs)) : Optional.empty();
                    }
                }
            });
        } catch (SQLException e) {
            log.log(Level.WARNING, "findPlayerCi error", e);
            return Optional.empty();
        }
    }

    /** Установить новый пароль существующему аккаунту (после сброса админом). */
    public void setPassword(UUID uuid, String passwordHash) throws SQLException {
        withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET password_hash = ? WHERE uuid = ?")) {
                ps.setString(1, passwordHash);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Сбросить сессию (например, /logout) — при следующем входе спросим пароль. */
    public void clearSession(UUID uuid) throws SQLException {
        withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET session_ip = NULL, session_expires = NULL WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Админ: полный сброс — пароль, привязка Telegram и сессия удаляются. */
    public void adminResetAccount(UUID uuid, long now) throws SQLException {
        withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET password_hash = NULL, tg_id = NULL, "
                            + "session_ip = NULL, session_expires = NULL WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pending_links SET status = 'expired' "
                            + "WHERE player_uuid = ? AND status = 'open'")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Админ: сброс только пароля (привязка Telegram и ник сохраняются). */
    public void adminResetPassword(UUID uuid) throws SQLException {
        withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET password_hash = NULL, "
                            + "session_ip = NULL, session_expires = NULL WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void updateLastLogin(UUID uuid, String ip, long now) throws SQLException {
        withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET last_ip = ?, last_login_ts = ? WHERE uuid = ?")) {
                ps.setString(1, ip);
                ps.setLong(2, now);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Выдать/обновить сессию (вход без пароля при перезаходе с того же IP). */
    public void updateSession(UUID uuid, String ip, long expiresAt) throws SQLException {
        withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET session_ip = ?, session_expires = ?, last_ip = ?, last_login_ts = ? "
                            + "WHERE uuid = ?")) {
                ps.setString(1, ip);
                ps.setLong(2, expiresAt);
                ps.setString(3, ip);
                ps.setLong(4, System.currentTimeMillis() / 1000L);
                ps.setString(5, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Сколько аккаунтов зарегистрировано с этого IP (лимит регистраций). */
    public long countPlayersByRegIp(String ip) {
        try {
            return withConn(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM players WHERE reg_ip = ?")) {
                    ps.setString(1, ip);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            });
        } catch (SQLException e) {
            log.log(Level.WARNING, "countPlayersByRegIp error", e);
            return -1L; // неизвестно — регистрацию не блокируем
        }
    }

    // ---------------- ip_bans (временный бан IP за перебор пароля) ----------------

    /** Все активные баны: ip -> epoch-сек окончания (для памяти плагина при старте). */
    public Map<String, Long> allIpBans() throws SQLException {
        return withConn(c -> {
            Map<String, Long> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ip, banned_until FROM ip_bans WHERE banned_until > ?")) {
                ps.setLong(1, System.currentTimeMillis() / 1000L);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString("ip"), rs.getLong("banned_until"));
                    }
                }
            }
            return out;
        });
    }

    /** Забанить IP до until (epoch-сек). Если бан уже длиннее — оставляем старый. */
    public void addIpBan(String ip, long until) throws SQLException {
        withConn(c -> {
            long existing = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT banned_until FROM ip_bans WHERE ip = ?")) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existing = rs.getLong("banned_until");
                    }
                }
            }
            if (existing >= until) {
                return null; // уже забанен дольше — не укорачиваем
            }
            if (existing > 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE ip_bans SET banned_until = ? WHERE ip = ?")) {
                    ps.setLong(1, until);
                    ps.setString(2, ip);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO ip_bans (ip, banned_until) VALUES (?, ?)")) {
                    ps.setString(1, ip);
                    ps.setLong(2, until);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ---------------- pending_links (привязка TG) ----------------

    /** Создаёт одноразовый код привязки (6 цифр); возвращает id созданной строки. */
    public long createPendingLink(UUID uuid, long ttlSec, long now) throws SQLException {
        SecureRandom rnd = new SecureRandom();
        for (int attempt = 0; attempt < 8; attempt++) {
            String code = String.format("%06d", rnd.nextInt(1_000_000));
            final String c = code;
            Long id = withConn(conn -> {
                boolean dup;
                try (PreparedStatement dupPs = conn.prepareStatement(
                        "SELECT code FROM pending_links WHERE code = ? FETCH FIRST 1 ROWS ONLY")) {
                    dupPs.setString(1, c);
                    try (ResultSet rs = dupPs.executeQuery()) {
                        dup = rs.next();
                    }
                }
                if (dup) {
                    return null;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO pending_links (player_uuid, code, status, created_ts, expires_ts) "
                                + "VALUES (?, ?, 'open', ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, c);
                    ps.setLong(3, now);
                    ps.setLong(4, now + ttlSec);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            return keys.getLong(1);
                        }
                    }
                }
                return null;
            });
            if (id != null) {
                return id;
            }
        }
        throw new SQLException("Не удалось сгенерировать уникальный код привязки");
    }

    /** true = игрок подтвердил привязку (бот вызвал /api/link). */
    public boolean isLinkBound(long linkId) {
        try {
            return withConn(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT status FROM pending_links WHERE id = ?")) {
                    ps.setLong(1, linkId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() && "bound".equals(rs.getString("status"));
                    }
                }
            });
        } catch (SQLException e) {
            log.log(Level.WARNING, "isLinkBound error", e);
            return false;
        }
    }

    /** Код привязки по id (показываем игроку в /addtg). */
    public String linkCode(long linkId) throws SQLException {
        return withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT code FROM pending_links WHERE id = ?")) {
                ps.setLong(1, linkId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString("code") : null;
                }
            }
        });
    }

    /** Живой (не истёкший) код привязки игрока: есть — новый не создаём. */
    public LinkInfo findOpenLink(UUID uuid, long now) throws SQLException {
        return withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, code, expires_ts FROM pending_links "
                            + "WHERE player_uuid = ? AND status = 'open' AND expires_ts > ? "
                            + "FETCH FIRST 1 ROWS ONLY")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new LinkInfo(rs.getLong("id"), rs.getString("code"),
                                rs.getLong("expires_ts"));
                    }
                    return null;
                }
            }
        });
    }

    /** Незакрытые просроченные коды помечаем использованными (гигиена БД). */
    public void expireStaleLinks(UUID uuid, long now) throws SQLException {
        withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pending_links SET status = 'expired' "
                            + "WHERE player_uuid = ? AND status = 'open' AND expires_ts <= ?")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Отменить конкретный код привязки (/addtg cancel). */
    public void expireLink(long id) throws SQLException {
        withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pending_links SET status = 'expired' WHERE id = ? AND status = 'open'")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Строка живого кода привязки. */
    public static final class LinkInfo {
        public final long id;
        public final String code;
        public final long expires;

        LinkInfo(long id, String code, long expires) {
            this.id = id;
            this.code = code;
            this.expires = expires;
        }
    }

    // ---------------- login_requests (2FA) ----------------

    public long createLoginRequest(UUID uuid, String nickname, String ip, long ttlSec, long now) throws SQLException {
        return withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO login_requests (player_uuid, nickname, ip, status, created_ts, expires_ts) "
                            + "VALUES (?, ?, ?, 'pending', ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, nickname);
                ps.setString(3, ip);
                ps.setLong(4, now);
                ps.setLong(5, now + ttlSec);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                    throw new SQLException("login_requests id не получен");
                }
            }
        });
    }

    /** pending | notified | confirmed | denied | expired | not_found */
    public String pollLoginRequest(long requestId) {
        try {
            return withConn(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT status, expires_ts FROM login_requests WHERE id = ?")) {
                    ps.setLong(1, requestId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return "not_found";
                        }
                        String st = rs.getString("status");
                        if (rs.getLong("expires_ts") < System.currentTimeMillis() / 1000L) {
                            return "expired";
                        }
                        return st;
                    }
                }
            });
        } catch (SQLException e) {
            log.log(Level.WARNING, "pollLoginRequest error", e);
            return "not_found";
        }
    }

    // ---------------- HTTP API для бота ----------------

    /** Список ожидающих подтверждения 2FA-запросов (для бота), только с привязкой TG. */
    public List<PendingRequest> listPendingRequests(long now) throws SQLException {
        return withConn(c -> {
            List<PendingRequest> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT lr.id, lr.player_uuid, lr.nickname, lr.ip, p.tg_id "
                            + "FROM login_requests lr, players p "
                            + "WHERE p.uuid = lr.player_uuid AND lr.status = 'pending' "
                            + "AND lr.expires_ts > ? AND p.tg_id IS NOT NULL "
                            + "ORDER BY lr.id ASC FETCH FIRST 50 ROWS ONLY")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new PendingRequest(
                                rs.getLong("id"),
                                rs.getString("player_uuid"),
                                rs.getString("nickname"),
                                rs.getString("ip"),
                                rs.getLong("tg_id")));
                    }
                }
            }
            return out;
        });
    }

    /** Бот подтвердил/отклонил вход. true — если запрос был живой и обновлён. */
    public boolean resolveLoginRequest(long id, String status) throws SQLException {
        Boolean res = withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE login_requests SET status = ? WHERE id = ? "
                            + "AND status IN ('pending','notified') AND expires_ts >= ?")) {
                ps.setString(1, status);
                ps.setLong(2, id);
                ps.setLong(3, System.currentTimeMillis() / 1000L);
                return ps.executeUpdate() == 1;
            }
        });
        return Boolean.TRUE.equals(res);
    }

    /**
     * Привязка Telegram по коду из /addtg (вызывается HTTP API бота).
     * @return ник игрока при успехе, null если код неверный/истёк/уже использован.
     */
    public String linkByCode(String code, long tgId, long now) throws SQLException {
        return withConn(c -> {
            try {
                c.setAutoCommit(false);
                String playerUuid;
                // 1) «занимаем» код (только open и не истёкший)
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE pending_links SET status = 'bound' "
                                + "WHERE code = ? AND status = 'open' AND expires_ts >= ?")) {
                    ps.setString(1, code);
                    ps.setLong(2, now);
                    if (ps.executeUpdate() != 1) {
                        c.rollback();
                        return null;
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT player_uuid FROM pending_links WHERE code = ? AND status = 'bound'")) {
                    ps.setString(1, code);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return null;
                        }
                        playerUuid = rs.getString("player_uuid");
                    }
                }
                // 2) пишем tg_id игроку и читаем ник
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE players SET tg_id = ? WHERE uuid = ?")) {
                    ps.setLong(1, tgId);
                    ps.setString(2, playerUuid);
                    ps.executeUpdate();
                }
                String nickname;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT nickname FROM players WHERE uuid = ?")) {
                    ps.setString(1, playerUuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return null;
                        }
                        nickname = rs.getString("nickname");
                    }
                }
                c.commit();
                return nickname;
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            } finally {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        });
    }
}
