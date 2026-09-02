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
        // проверка соединения
        try (Connection c = newConnection()) {
            c.isValid(3);
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
}
