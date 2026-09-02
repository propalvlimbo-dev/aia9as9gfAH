package ru.elytrix.auth;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Тонкий слой поверх MariaDB (JDBC). Пул соединений попроще, но рабочий. */
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

    private final String url;
    private final String user;
    private final String password;
    private final int maxSize;
    private final Logger log;

    private final Deque<Connection> idle = new ArrayDeque<>();
    private int open;

    public Database(PluginConfig cfg, Logger log) throws SQLException {
        this.url = "jdbc:mariadb://" + cfg.dbHost() + ":" + cfg.dbPort() + "/" + cfg.dbName()
                + "?connectTimeout=" + cfg.dbConnTimeout()
                + "&socketTimeout=" + cfg.dbSockTimeout()
                + "&useUnicode=true&characterEncoding=utf8"
                + "&useServerPrepStmts=false&cachePrepStmts=false";
        this.user = cfg.dbUser();
        this.password = cfg.dbPassword();
        this.maxSize = cfg.dbPoolSize();
        this.log = log;
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("mariadb-java-client не вшит в jar плагина", e);
        }
        // проверка соединения + автосоздание таблиц (schema не нужна руками)
        try (Connection c = newConnection()) {
            c.isValid(3);
            ensureSchema(c);
        }
    }

    /** Таблицы создаются сами при старте (CREATE TABLE IF NOT EXISTS). */
    private void ensureSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS players ("
                    + " uuid CHAR(36) NOT NULL,"
                    + " nickname VARCHAR(16) NOT NULL,"
                    + " password_hash VARCHAR(255) DEFAULT NULL,"
                    + " tg_id BIGINT DEFAULT NULL,"
                    + " reg_ip VARCHAR(45) DEFAULT NULL,"
                    + " reg_ts BIGINT NOT NULL,"
                    + " last_ip VARCHAR(45) DEFAULT NULL,"
                    + " last_login_ts BIGINT DEFAULT NULL,"
                    + " PRIMARY KEY (uuid),"
                    + " UNIQUE KEY uq_players_nickname (nickname),"
                    + " KEY idx_players_tg (tg_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            st.execute("CREATE TABLE IF NOT EXISTS pending_links ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " player_uuid CHAR(36) NOT NULL,"
                    + " code VARCHAR(10) NOT NULL,"
                    + " status ENUM('open','bound','expired') NOT NULL DEFAULT 'open',"
                    + " created_ts BIGINT NOT NULL,"
                    + " expires_ts BIGINT NOT NULL,"
                    + " KEY idx_links_status (status, expires_ts),"
                    + " KEY idx_links_code (code)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            st.execute("CREATE TABLE IF NOT EXISTS login_requests ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " player_uuid CHAR(36) NOT NULL,"
                    + " nickname VARCHAR(16) NOT NULL,"
                    + " ip VARCHAR(45) DEFAULT NULL,"
                    + " status ENUM('pending','notified','confirmed','denied','expired') NOT NULL DEFAULT 'pending',"
                    + " created_ts BIGINT NOT NULL,"
                    + " expires_ts BIGINT NOT NULL,"
                    + " KEY idx_requests_status (status, expires_ts),"
                    + " KEY idx_requests_player (player_uuid)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        }
    }

    private Connection newConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private synchronized Connection acquire() throws SQLException {
        for (int i = 0; i < 50; i++) { // ~2.5 сек ожидания максимум
            Connection c = idle.poll();
            if (c != null) {
                if (c.isValid(2)) {
                    return c;
                }
                closeQuiet(c);
                open--;
                continue;
            }
            if (open < maxSize) {
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

    public synchronized void close() {
        Connection c;
        while ((c = idle.poll()) != null) {
            closeQuiet(c);
        }
        open = 0;
    }

    private void closeQuiet(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
        }
    }

    /** Заодно регенерирует pool при ошибках соединения: discard + retry один раз. */
    private <T> T withConn(SqlWork<T> work) throws SQLException {
        Connection c = acquire();
        try {
            return work.run(c);
        } catch (SQLException e) {
            discard(c); // соединение, вероятно, битое
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

    public Optional<PlayerRow> findPlayer(String nickname) {
        try {
            return withConn(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, nickname, password_hash, tg_id FROM players WHERE nickname = ?")) {
                    ps.setString(1, nickname);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return Optional.of(new PlayerRow(
                                    UUID.fromString(rs.getString("uuid")),
                                    rs.getString("nickname"),
                                    rs.getString("password_hash"),
                                    rs.getObject("tg_id") == null ? null : rs.getLong("tg_id")));
                        }
                        return Optional.empty();
                    }
                }
            });
        } catch (SQLException e) {
            log.log(Level.WARNING, "findPlayer error", e);
            return Optional.empty();
        }
    }

    public Optional<PlayerRow> findPlayerByUuid(UUID uuid) {
        try {
            return withConn(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, nickname, password_hash, tg_id FROM players WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return Optional.of(new PlayerRow(
                                    uuid,
                                    rs.getString("nickname"),
                                    rs.getString("password_hash"),
                                    rs.getObject("tg_id") == null ? null : rs.getLong("tg_id")));
                        }
                        return Optional.empty();
                    }
                }
            });
        } catch (SQLException e) {
            log.log(Level.WARNING, "findPlayerByUuid error", e);
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

    /** Используется ботом при /link; плагину нужно только читать tg_id. */
    public void setTgId(UUID uuid, long tgId) throws SQLException {
        withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE players SET tg_id = ? WHERE uuid = ?")) {
                ps.setLong(1, tgId);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---------------- pending_links (привязка TG) ----------------

    /** Создаёт код с проверкой на коллизию среди открытых кодов. */
    public int createPendingLink(UUID uuid, long ttlSec, long now) throws SQLException {
        SecureRandom rnd = new SecureRandom();
        for (int attempt = 0; attempt < 6; attempt++) {
            String code = String.valueOf(rnd.nextInt(100_000_000));
            code = ("00000000" + code).substring(code.length()); // 8 цифр
            final String c = code;
            Integer id = withConn(conn -> {
                try (PreparedStatement dup = conn.prepareStatement(
                        "SELECT 1 FROM pending_links WHERE code = ? AND status = 'open' LIMIT 1")) {
                    dup.setString(1, c);
                    try (ResultSet rs = dup.executeQuery()) {
                        if (rs.next()) {
                            return null;
                        }
                    }
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
                            return keys.getInt(1);
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

    /** true = игрок подтвердил привязку в боте. */
    public boolean isLinkBound(int linkId) {
        try {
            return withConn(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT status FROM pending_links WHERE id = ?")) {
                    ps.setInt(1, linkId);
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

    /** Код привязки по id (нужен, чтобы показать игроку). */
    public String linkCode(int linkId) throws SQLException {
        return withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT code FROM pending_links WHERE id = ?")) {
                ps.setInt(1, linkId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("code");
                    }
                    return null;
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

    /** pending | confirmed | denied | expired | not_found */
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
                        if (("pending".equals(st) || "notified".equals(st)
                                || "confirmed".equals(st) || "denied".equals(st))
                                && rs.getLong("expires_ts") < System.currentTimeMillis() / 1000L) {
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

    /** Список ожидающих подтверждения 2FA-запросов (для бота), только с привязкой TG. */
    public java.util.List<PendingRequest> listPendingRequests(long now) throws SQLException {
        return withConn(c -> {
            java.util.List<PendingRequest> out = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT lr.id, lr.player_uuid, lr.nickname, lr.ip, p.tg_id "
                            + "FROM login_requests lr JOIN players p ON p.uuid = lr.player_uuid "
                            + "WHERE lr.status = 'pending' AND lr.expires_ts > ? AND p.tg_id IS NOT NULL "
                            + "ORDER BY lr.id ASC LIMIT 50")) {
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
                // 1) "занимаем" код (только open и не истёкший)
                String playerUuid;
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
                // 2) пишем tg_id игроку
                String nickname;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE players SET tg_id = ? WHERE uuid = ?")) {
                    ps.setLong(1, tgId);
                    ps.setString(2, playerUuid);
                    ps.executeUpdate();
                }
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
