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
import java.util.List;
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

        PlayerRow(UUID uuid, String nickname, String passwordHash, Long tgId) {
            this.uuid = uuid;
            this.nickname = nickname;
            this.passwordHash = passwordHash;
            this.tgId = tgId;
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
        // HSQLDB умеет писать служебные INFO (dataFileCache open, checkpoint и т.п.)
        // и через java.util.logging, и через свой SimpleLog в System.out.
        // NullCordX выводит всё это в консоль как ERROR, поэтому глушим JUL-логгер
        // насовсем, а потоки System.out/err перехватываем на время инициализации БД
        // (самый шумный момент — открытие файла БД и создание таблиц).
        try {
            java.util.logging.Logger.getLogger("org.hsqldb").setLevel(Level.OFF);
        } catch (SecurityException ignored) {
        }
        this.url = "jdbc:hsqldb:file:" + dbFile.getAbsolutePath()
                + ";hsqldb.lock_file=false;hsqldb.default_table_type=cached;sql.syntax_mys=true";
        try {
            Class.forName("org.hsqldb.jdbc.JDBCDriver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("HSQLDB driver не вшит в jar плагина", e);
        }

        java.io.PrintStream realOut = System.out;
        java.io.PrintStream realErr = System.err;
        java.io.ByteArrayOutputStream suppressed = new java.io.ByteArrayOutputStream();
        try {
            // HSQLDB (SimpleLog) пишет свои INFO в System.out, а не в JUL/SLF4J,
            // поэтому на время инициализации БД глушим оба потока.
            java.io.PrintStream silent = new java.io.PrintStream(suppressed, true,
                    java.nio.charset.StandardCharsets.UTF_8);
            System.setOut(silent);
            System.setErr(silent);
            try (Connection c = DriverManager.getConnection(url)) {
                ensureSchema(c);
            }
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
        if (suppressed.size() > 0) {
            log.log(Level.FINE, "HSQLDB init log подавлен (" + suppressed.size() + " байт)");
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
                    + " last_login_ts BIGINT"
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
        }
        // индексы (HSQLDB не понимает IF NOT EXISTS — проверяем через системные таблицы)
        createIndexIfMissing(c, "players", "idx_players_tg", "CREATE INDEX idx_players_tg ON players(tg_id)");
        createIndexIfMissing(c, "pending_links", "idx_links_code", "CREATE INDEX idx_links_code ON pending_links(code)");
        createIndexIfMissing(c, "pending_links", "idx_links_status", "CREATE INDEX idx_links_status ON pending_links(status, expires_ts)");
        createIndexIfMissing(c, "login_requests", "idx_requests_status", "CREATE INDEX idx_requests_status ON login_requests(status, expires_ts)");
        createIndexIfMissing(c, "login_requests", "idx_requests_player", "CREATE INDEX idx_requests_player ON login_requests(player_uuid)");
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
        return new PlayerRow(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("nickname"),
                rs.getString("password_hash"),
                rs.getObject("tg_id") == null ? null : rs.getLong("tg_id"));
    }

    public Optional<PlayerRow> findPlayer(String nickname) {
        try {
            return withConn(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, nickname, password_hash, tg_id FROM players WHERE nickname = ?")) {
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

    // ---------------- pending_links (привязка TG) ----------------

    /** Создаёт одноразовый код привязки; возвращает id созданной строки. */
    public long createPendingLink(UUID uuid, long ttlSec, long now) throws SQLException {
        SecureRandom rnd = new SecureRandom();
        for (int attempt = 0; attempt < 8; attempt++) {
            String code = String.format("%08d", rnd.nextInt(100_000_000));
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
