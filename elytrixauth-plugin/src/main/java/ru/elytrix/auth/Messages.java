package ru.elytrix.auth;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * messages.yml: все сообщения плагина в одном файле.
 *
 * Поддерживается простой YAML-подмножество:
 *   key: значение
 *   список:
 *     - строка 1
 *     - строка 2
 * Комментарии (#) и пустые строки игнорируются. Кавычки у значений срезаются.
 * Цвета: &-коды и &#RRGGBB. Плейсхолдеры: {prefix}, {player}, {min} и т.д.
 */
public final class Messages {

    private final Map<String, List<String>> data = new LinkedHashMap<>();
    private String prefix = "";

    private Messages() {
    }

    /** Загрузка из файла. Если файла нет — файл создаётся из дефолта внутри jar. */
    public static Messages load(File file, Logger log) {
        Messages m = new Messages();
        if (!file.exists()) {
            saveDefault(file);
        }
        if (!file.exists()) {
            log.warning("messages.yml не создан — сообщения будут пустыми.");
            return m;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            String currentKey = null;
            for (String raw : lines) {
                String line = raw;
                if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                    continue;
                }
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    // элемент списка
                    String t = line.trim();
                    if (currentKey != null && t.startsWith("- ")) {
                        m.data.computeIfAbsent(currentKey, k -> new ArrayList<>())
                                .add(unescape(stripQuotes(t.substring(2).trim())));
                    }
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                currentKey = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                m.data.putIfAbsent(currentKey, new ArrayList<>());
                if (!value.isEmpty() && !"-".equals(value)) {
                    m.data.get(currentKey).add(unescape(stripQuotes(value)));
                }
            }
            List<String> pfx = m.data.get("prefix");
            if (pfx != null && !pfx.isEmpty()) {
                m.prefix = pfx.get(0);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Не удалось прочитать messages.yml: " + e.getMessage(), e);
        }
        return m;
    }

    private static void saveDefault(File target) {
        try (InputStream in = Messages.class.getResourceAsStream("/messages.yml")) {
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

    private static String stripQuotes(String v) {
        if (v.length() >= 2) {
            char a = v.charAt(0);
            char b = v.charAt(v.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    /** "\n" (два символа) в файле → реальный перенос строки. */
    private static String unescape(String v) {
        return v.replace("\\n", "\n");
    }

    // ---------------------------------------------------------------- доступ

    /** Строка с &-кодами (плейсхолдеры подставлены, префикс уже вставлен через {prefix}). */
    public String raw(String key, String... args) {
        List<String> lines = data.get(key);
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return apply(lines.get(0), args);
    }

    /** Список строк с &-кодами. */
    public List<String> rawList(String key, String... args) {
        List<String> lines = data.get(key);
        if (lines == null || lines.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) {
            out.add(apply(l, args));
        }
        return out;
    }

    private String apply(String text, String... args) {
        String s = text.replace("{prefix}", prefix);
        for (int i = 0; i + 1 < args.length; i += 2) {
            String name = args[i];
            String val = args[i + 1];
            if (val == null) {
                val = "";
            }
            s = s.replace("{" + name + "}", val);
        }
        return s;
    }

    // ---------------------------------------------------------------- цвета

    /**
     * Переводит &-коды и &#RRGGBB в legacy-формат Bungee (§, &x…),
     * который понимает TextComponent.fromLegacyText.
     */
    public static String legacy(String textWithAmp) {
        if (textWithAmp == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(textWithAmp.length() + 16);
        int i = 0;
        int n = textWithAmp.length();
        while (i < n) {
            char c = textWithAmp.charAt(i);
            if (c == '&' && i + 1 < n && textWithAmp.charAt(i + 1) == '#') {
                // &#RRGGBB
                if (i + 8 <= n && isHex(textWithAmp, i + 2, i + 8)) {
                    sb.append("§x");
                    for (int k = i + 2; k < i + 8; k++) {
                        sb.append('§').append(textWithAmp.charAt(k));
                    }
                    i += 9;
                    continue;
                }
            }
            if (c == '&' && i + 1 < n && isLegacyCode(textWithAmp.charAt(i + 1))) {
                sb.append('§').append(textWithAmp.charAt(i + 1));
                i += 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean isLegacyCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'k' && c <= 'o')
                || c == 'r' || (c >= 'A' && c <= 'F') || (c >= 'K' && c <= 'O') || c == 'R';
    }

    private static boolean isHex(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- отправка

    /** Компоненты для чата/actionbar/title (из строки с &-кодами). */
    public static BaseComponent[] comp(String textWithAmp) {
        return TextComponent.fromLegacyText(legacy(textWithAmp));
    }

    public BaseComponent[] compKey(String key, String... args) {
        return comp(raw(key, args));
    }

    public void chat(ProxiedPlayer p, String key, String... args) {
        BaseComponent[] c = compKey(key, args);
        if (c.length > 0) {
            p.sendMessage(c);
        }
    }

    public void chatList(ProxiedPlayer p, String key, String... args) {
        for (String line : rawList(key, args)) {
            p.sendMessage(comp(line));
        }
    }

    public void actionbar(ProxiedPlayer p, String key, String... args) {
        BaseComponent[] c = compKey(key, args);
        if (c.length > 0) {
            p.sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, c);
        }
    }

    public void kick(ProxiedPlayer p, String key, String... args) {
        String text = raw(key, args);
        p.disconnect(comp(text));
    }
}
