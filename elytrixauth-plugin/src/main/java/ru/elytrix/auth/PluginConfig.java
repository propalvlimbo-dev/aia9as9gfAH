package ru.elytrix.auth;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.List;
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

    /**
     * Гарантирует, что в конфиге есть api.secret (генерирует, если CHANGE_ME/пусто).
     * @return актуальный секрет.
     */
    public static String ensureApiSecret(File file) {
        String secret = null;
        try {
            PluginConfig cfg = new PluginConfig(file);
            String cur = cfg.get("api.secret", "CHANGE_ME");
            if (cur != null && !cur.isEmpty() && !"CHANGE_ME".equals(cur)) {
                return cur;
            }
            byte[] rnd = new byte[24];
            new SecureRandom().nextBytes(rnd);
            StringBuilder sb = new StringBuilder();
            for (byte b : rnd) {
                sb.append(String.format("%02x", b));
            }
            secret = sb.toString();
        } catch (IOException e) {
            return null;
        }
        // переписываем строку api.secret (или добавляем в конец)
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("api.secret=")) {
                    lines.set(i, "api.secret=" + secret);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add("api.secret=" + secret);
            }
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        return secret;
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

    public String authServer()   { return get("auth.server", "auth"); }
    public String targetServer() { return get("target.server", "grief"); }

    /** Путь к файлу встроенной БД (HSQLDB). Относительный — от папки плагина. */
    public String dbFile()       { return get("db.file", "db/elytrix"); }

    public int    loginTimeout() { return getInt("login.timeout.seconds", 180); }
    public int    maxTries()     { return Math.max(1, getInt("max.failed.tries", 5)); }
    public int    tryWindow()    { return getInt("failed.window.seconds", 60); }
    public int    minPassword()  { return getInt("min.password.length", 4); }

    public int    linkTtl()      { return getInt("link.code.ttl.seconds", 300); }
    public int    login2faTtl()  { return getInt("login2fa.ttl.seconds", 90); }

    public int    apiPort()      { return getInt("api.port", 8754); }
    public String apiSecret()    { return get("api.secret", ""); }
    public String apiBind()      { return get("api.bind", "0.0.0.0"); }
}
