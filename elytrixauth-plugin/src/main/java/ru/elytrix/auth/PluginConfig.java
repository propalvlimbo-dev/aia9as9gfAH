package ru.elytrix.auth;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/** Конфигурация ElytrixAuth (config.properties, UTF-8, key=value). */
public final class PluginConfig {

    private final Properties props = new Properties();

    public PluginConfig(File file) throws IOException {
        if (!file.exists()) {
            throw new IOException("config.properties не найден: " + file);
        }
        try (InputStream in = Files.newInputStream(file.toPath());
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
    }

    /** Копирует дефолтный config.properties из jar в папку плагина, если его нет. */
    public static void saveDefaultConfig(File target) {
        if (target.exists()) {
            return;
        }
        try (InputStream in = ElytrixAuthPlugin.class.getResourceAsStream("/config.properties")) {
            if (in == null) {
                return;
            }
            if (target.getParentFile() != null) {
                target.getParentFile().mkdirs();
            }
            try (OutputStream out = Files.newOutputStream(target.toPath())) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private String get(String key, String def) {
        String v = props.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    private int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String dbHost()       { return get("db.host", "127.0.0.1"); }
    public int    dbPort()       { return getInt("db.port", 3306); }
    public String dbName()       { return get("db.name", "elytrix"); }
    public String dbUser()       { return get("db.user", "elytrix"); }
    public String dbPassword()   { return get("db.password", ""); }
    public int    dbPoolSize()   { return Math.max(1, getInt("db.pool.size", 4)); }
    public int    dbConnTimeout(){ return getInt("db.connect.timeout.ms", 3000); }
    public int    dbSockTimeout(){ return getInt("db.socket.timeout.ms", 5000); }

    public String authServer()   { return get("auth.server", "auth"); }
    public String targetServer() { return get("target.server", "grief"); }

    public int    loginTimeout() { return getInt("login.timeout.seconds", 180); }
    public int    maxTries()     { return Math.max(1, getInt("max.failed.tries", 5)); }
    public int    tryWindow()    { return getInt("failed.window.seconds", 60); }
    public int    minPassword()  { return getInt("min.password.length", 4); }

    public int    linkTtl()      { return getInt("link.code.ttl.seconds", 300); }
    public int    login2faTtl()  { return getInt("login2fa.ttl.seconds", 90); }

    public int    apiPort()      { return getInt("api.port", 8754); }
    public String apiSecret()    { return get("api.secret", "CHANGE_ME"); }
    public String apiBind()      { return get("api.bind", "0.0.0.0"); }
}
