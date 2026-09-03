package ru.elytrix.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Мини-HTTP API для Telegram-бота.
 * Бот НЕ ходит в БД — только сюда (это безопаснее: базу наружу открывать не нужно).
 *
 *   GET  /api/health            -> {"ok":true}
 *   GET  /api/pending           -> {"requests":[{id,player_uuid,nickname,ip,tg_id}]}
 *   POST /api/resolve           -> {"action":"confirm|deny","id":123}
 *   POST /api/link              -> {"code":"123456","tg_id":123456} -> {"nickname":...}
 *   GET  /api/accounts?tg_id=   -> {"accounts":[{uuid,nickname,online,tg2fa}]}
 *   POST /api/kick              -> {"nickname":...,"tg_id":...} -> {"online":true/false}
 *   POST /api/toggle2fa         -> {"nickname":...,"tg_id":...} -> {"tg2fa":true/false}
 *   POST /api/password          -> {"nickname":...,"tg_id":...,"password":...}
 *   GET  /api/alerts            -> {"alerts":[{tg_id,text}]} (уведомления о входах)
 *
 * Авторизация: заголовок X-Api-Key: <api.secret из config.properties>
 */
public final class ApiServer {

    private final ElytrixAuthPlugin plugin;
    private final PluginConfig cfg;
    private final Database db;
    private final Logger log;
    private HttpServer server;

    public ApiServer(ElytrixAuthPlugin plugin, PluginConfig cfg, Database db, Logger log) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.db = db;
        this.log = log;
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        int port = cfg.apiPort();
        String bind = cfg.apiBind();
        server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/pending", this::handlePending);
        server.createContext("/api/resolve", this::handleResolve);
        server.createContext("/api/link", this::handleLink);
        server.createContext("/api/accounts", this::handleAccounts);
        server.createContext("/api/kick", this::handleKick);
        server.createContext("/api/toggle2fa", this::handleToggle2fa);
        server.createContext("/api/password", this::handlePassword);
        server.createContext("/api/alerts", this::handleAlerts);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "elytrix-api");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        log.info("HTTP API для бота запущен на " + bind + ":" + port);
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean authorized(HttpExchange ex) {
        String key = ex.getRequestHeaders().getFirst("X-Api-Key");
        String expected = cfg.apiSecret();
        return expected != null && !expected.isEmpty()
                && !"CHANGE_ME".equals(expected)
                && expected.equals(key);
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void respondJson(HttpExchange ex, int code, String body) {
        try {
            respond(ex, code, body);
        } catch (IOException e) {
            log.log(Level.WARNING, "api respond error", e);
        }
    }

    private void respondError(HttpExchange ex, int code, String message) {
        respondJson(ex, code, "{\"ok\":false,\"error\":" + jsonStr(message) + "}");
    }

    private static String jsonStr(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (java.io.InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Владеет ли аккаунт указанным Telegram. tg_id в теле — от бота: действия только по своим аккаунтам. */
    private static boolean ownsTg(String body, Database.PlayerRow row) {
        String tgRaw = firstField(body, "tg_id");
        if (tgRaw == null || tgRaw.isEmpty()) {
            return true; // без tg_id (curl/консоль) достаточно ключа API
        }
        long tgId;
        try {
            tgId = Long.parseLong(tgRaw.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        return row.tgId != null && row.tgId == tgId;
    }

    /** Значение query-параметра из URI (например tg_id из ?tg_id=123). */
    private static String queryField(java.net.URI uri, String key) {
        String q = uri == null ? null : uri.getQuery();
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return part.substring(eq + 1);
            }
        }
        return null;
    }

    private static String firstField(String body, String key) {
        // примитивный JSON-парсинг: "key":"value" или "key":123
        String quoted = "\"" + key + "\"";
        int i = body.indexOf(quoted);
        if (i < 0) {
            return null;
        }
        int colon = body.indexOf(':', i + quoted.length());
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
            start++;
        }
        if (start < body.length() && body.charAt(start) == '"') {
            StringBuilder sb = new StringBuilder();
            int j = start + 1;
            while (j < body.length()) {
                char ch = body.charAt(j);
                if (ch == '\\' && j + 1 < body.length()) {
                    sb.append(body.charAt(j + 1));
                    j += 2;
                    continue;
                }
                if (ch == '"') {
                    break;
                }
                sb.append(ch);
                j++;
            }
            return sb.toString();
        }
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)))) {
            end++;
        }
        return end > start ? body.substring(start, end) : null;
    }

    // ------------------------------------------------------------------ handlers

    private void handleHealth(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            respondError(ex, 401, "unauthorized");
            return;
        }
        respondJson(ex, 200, "{\"ok\":true}");
    }

    private void handlePending(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            respondError(ex, 401, "unauthorized");
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respondError(ex, 405, "method not allowed");
            return;
        }
        try {
            List<Database.PendingRequest> list = db.listPendingRequests(ElytrixAuthPlugin.now());
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"requests\":[");
            for (int i = 0; i < list.size(); i++) {
                Database.PendingRequest r = list.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":").append(r.id)
                        .append(",\"player_uuid\":").append(jsonStr(r.playerUuid))
                        .append(",\"nickname\":").append(jsonStr(r.nickname))
                        .append(",\"ip\":").append(jsonStr(r.ip))
                        .append(",\"tg_id\":").append(r.tgId)
                        .append('}');
            }
            sb.append("]}");
            respondJson(ex, 200, sb.toString());
        } catch (SQLException e) {
            log.log(Level.WARNING, "pending error", e);
            respondError(ex, 500, "db error");
        }
    }

    private void handleResolve(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            respondError(ex, 401, "unauthorized");
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respondError(ex, 405, "method not allowed");
            return;
        }
        String body = readBody(ex);
        String action = firstField(body, "action");
        String idRaw = firstField(body, "id");
        if (!("confirm".equals(action) || "deny".equals(action)) || idRaw == null) {
            respondError(ex, 400, "bad request: action=confirm|deny, id required");
            return;
        }
        long id;
        try {
            id = Long.parseLong(idRaw.trim());
        } catch (NumberFormatException e) {
            respondError(ex, 400, "bad id");
            return;
        }
        try {
            boolean updated = db.resolveLoginRequest(id, "confirm".equals(action) ? "confirmed" : "denied");
            respondJson(ex, 200, updated ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"stale\"}");
        } catch (SQLException e) {
            log.log(Level.WARNING, "resolve error", e);
            respondError(ex, 500, "db error");
        }
    }

    private void handleLink(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            respondError(ex, 401, "unauthorized");
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respondError(ex, 405, "method not allowed");
            return;
        }
        String body = readBody(ex);
        String code = firstField(body, "code");
        String tgRaw = firstField(body, "tg_id");
        if (code == null || tgRaw == null) {
            respondError(ex, 400, "bad request: code and tg_id required");
            return;
        }
        long tgId;
        try {
            tgId = Long.parseLong(tgRaw.trim());
        } catch (NumberFormatException e) {
            respondError(ex, 400, "bad tg_id");
            return;
        }
        try {
            String nickname = db.linkByCode(code.trim(), tgId, ElytrixAuthPlugin.now());
            if (nickname == null) {
                respondJson(ex, 200, "{\"ok\":false,\"error\":\"invalid_or_expired_code\"}");
            } else {
                respondJson(ex, 200, "{\"ok\":true,\"nickname\":" + jsonStr(nickname) + "}");
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "link error", e);
            respondError(ex, 500, "db error");
        }
    }

    /** Аккаунты, привязанные к Telegram-пользователю (для панели управления в боте). */
    private void handleAccounts(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            respondError(ex, 401, "unauthorized");
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respondError(ex, 405, "method not allowed");
            return;
        }
        String tgRaw = queryField(ex.getRequestURI(), "tg_id");
        if (tgRaw == null) {
            respondError(ex, 400, "tg_id required");
            return;
        }
        long tgId;
        try {
            tgId = Long.parseLong(tgRaw.trim());
        } catch (NumberFormatException e) {
            respondError(ex, 400, "bad tg_id");
            return;
        }
        List<Database.PlayerRow> rows = db.listPlayersByTg(tgId);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"accounts\":[");
        for (int i = 0; i < rows.size(); i++) {
            Database.PlayerRow r = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            boolean online = plugin.proxy().getPlayer(r.uuid) != null;
            sb.append("{\"uuid\":").append(jsonStr(r.uuid.toString()))
                    .append(",\"nickname\":").append(jsonStr(r.nickname))
                    .append(",\"online\":").append(online)
                    .append(",\"tg2fa\":").append(r.tg2fa)
                    .append('}');
        }
        sb.append("]}");
        respondJson(ex, 200, sb.toString());
    }

    /** Кикнуть игрока (если он онлайн) — кнопка в боте. */
    private void handleKick(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            respondError(ex, 401, "unauthorized");
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respondError(ex, 405, "method not allowed");
            return;
        }
        String body = readBody(ex);
        String nickname = firstField(body, "nickname");
        if (nickname == null || nickname.isEmpty()) {
            respondError(ex, 400, "nickname required");
            return;
        }
        Database.PlayerRow row = db.findPlayerCi(nickname).orElse(null);
        if (row == null) {
            respondJson(ex, 200, "{\"ok\":false,\"error\":\"player_not_found\"}");
            return;
        }
        if (!ownsTg(body, row)) {
            respondJson(ex, 200, "{\"ok\":false,\"error\":\"not_yours\"}");
            return;
        }
        ProxiedPlayer p = plugin.proxy().getPlayer(row.uuid);
        if (p != null) {
            plugin.messages().kick(p, "kick-by-bot");
        }
        // сбрасываем сессию: после кика вход будет только по паролю
        // (+2FA или уведомление, смотря по настройке аккаунта)
        try {
            db.clearSession(row.uuid);
        } catch (SQLException e) {
            log.log(Level.WARNING, "clearSession(kick api) error", e);
        }
        respondJson(ex, 200, "{\"ok\":true,\"online\":" + (p != null) + "}");
    }

    /** Переключить 2FA (кнопка подтверждения входа) у аккаунта — кнопка в боте. */
    private void handleToggle2fa(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            respondError(ex, 401, "unauthorized");
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respondError(ex, 405, "method not allowed");
            return;
        }
        String body = readBody(ex);
        String nickname = firstField(body, "nickname");
        if (nickname == null || nickname.isEmpty()) {
            respondError(ex, 400, "nickname required");
            return;
        }
        Database.PlayerRow row = db.findPlayerCi(nickname).orElse(null);
        if (row == null) {
            respondJson(ex, 200, "{\"ok\":false,\"error\":\"player_not_found\"}");
            return;
        }
        if (!ownsTg(body, row)) {
            respondJson(ex, 200, "{\"ok\":false,\"error\":\"not_yours\"}");
            return;
        }
        try {
            boolean next = !row.tg2fa;
            db.setTg2fa(row.uuid, next);
            respondJson(ex, 200, "{\"ok\":true,\"tg2fa\":" + next + "}");
        } catch (SQLException e) {
            log.log(Level.WARNING, "setTg2fa error", e);
            respondError(ex, 500, "db error");
        }
    }

    /** Сменить пароль аккаунта — кнопка в боте. Сессия сбрасывается. */
    private void handlePassword(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            respondError(ex, 401, "unauthorized");
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respondError(ex, 405, "method not allowed");
            return;
        }
        String body = readBody(ex);
        String nickname = firstField(body, "nickname");
        String password = firstField(body, "password");
        if (nickname == null || nickname.isEmpty() || password == null) {
            respondError(ex, 400, "nickname and password required");
            return;
        }
        Database.PlayerRow row = db.findPlayerCi(nickname).orElse(null);
        if (row == null) {
            respondJson(ex, 200, "{\"ok\":false,\"error\":\"player_not_found\"}");
            return;
        }
        if (!ownsTg(body, row)) {
            respondJson(ex, 200, "{\"ok\":false,\"error\":\"not_yours\"}");
            return;
        }
        String rule = PasswordRules.check(cfg, row.nickname, password);
        if (rule != null) {
            respondJson(ex, 200, "{\"ok\":false,\"error\":\"" + rule + "\"}");
            return;
        }
        try {
            String hash = PasswordHash.create(password);
            db.setPassword(row.uuid, hash);
            db.clearSession(row.uuid); // следующий вход — по новому паролю
            respondJson(ex, 200, "{\"ok\":true}");
        } catch (SQLException e) {
            log.log(Level.WARNING, "setPassword(api) error", e);
            respondError(ex, 500, "db error");
        }
    }

    /** Уведомления боту (например, о входе при выключенной 2FA). */
    private void handleAlerts(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            respondError(ex, 401, "unauthorized");
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respondError(ex, 405, "method not allowed");
            return;
        }
        try {
            List<Database.AlertRow> alerts = db.takeAlerts();
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"alerts\":[");
            for (int i = 0; i < alerts.size(); i++) {
                Database.AlertRow a = alerts.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"tg_id\":").append(a.tgId)
                        .append(",\"text\":").append(jsonStr(a.text))
                        .append('}');
            }
            sb.append("]}");
            respondJson(ex, 200, sb.toString());
        } catch (SQLException e) {
            log.log(Level.WARNING, "alerts error", e);
            respondError(ex, 500, "db error");
        }
    }
}
