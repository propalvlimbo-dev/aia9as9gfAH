package ru.elytrix.auth;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
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

    /**
     * Компоненты для чата/actionbar/title из строки с кодами.
     *
     * Разбираем вручную (НЕ через fromLegacyText, который на части форков
     * портит hex-цвета): каждый «прогон» форматирования становится отдельным
     * TextComponent с явно выставленным цветом/стилем. Hex-цвета
     * (&#RRGGBB и &x&R&R&G&G&B&B) — через ChatColor.of, который даёт
     * корректный §x… или hex-JSON для клиента 1.16+.
     *
     * Поддерживаются кликабельные теги (плейсхолдеры подставляются раньше,
     * в raw/apply):
     *   [copy=текст]…[/copy]       — клик копирует текст (например /link {code})
     *   [suggest=текст]…[/suggest] — клик вставляет текст в строку ввода
     *   [run=команда]…[/run]       — клик выполняет команду
     *   [url=ссылка]…[/url]        — клик открывает ссылку
     * У каждого тега свой авто-hover-подсказка («нажми, чтобы…»).
     */
    public static BaseComponent[] comp(String textWithAmp) {
        TextComponent root = new TextComponent("");
        if (textWithAmp == null || textWithAmp.isEmpty()) {
            return new BaseComponent[]{root};
        }
        try {
            appendMarkup(root, textWithAmp);
        } catch (Throwable t) {
            // Любой сбой markup/API форка не должен ронять соединение:
            // рисуем обычный текст без кликабельных тегов.
            TextComponent fb = new TextComponent("");
            try {
                renderPlain(fb, stripMarkup(textWithAmp));
            } catch (Throwable t2) {
                fb = new TextComponent(stripMarkup(textWithAmp));
            }
            return new BaseComponent[]{fb};
        }
        return new BaseComponent[]{root};
    }

    /** Вырезает [action=...]...[/action] из строки (фолбэк при сбое markup). */
    private static String stripMarkup(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '[') {
                int close = text.indexOf(']', i + 1);
                if (close > i) {
                    String head = text.substring(i + 1, close);
                    int eq = head.indexOf('=');
                    if (eq > 0 && clickAction(head.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT)) != null) {
                        i = close + 1; // пропускаем открывающий тег
                        continue;
                    }
                }
                if (close > i + 1 && text.charAt(i + 1) == '/') {
                    i = close + 1; // пропускаем закрывающий тег
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** Разбор текста: обычные куски + кликабельные теги [action=value]…[/action]. */
    private static void appendMarkup(TextComponent root, String text) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            int open = text.indexOf('[', i);
            if (open < 0) {
                renderPlain(root, text.substring(i));
                break;
            }
            String actionName = null;
            String value = null;
            int contentStart = -1;
            int closeBracket = text.indexOf(']', open + 1);
            if (closeBracket > open) {
                String head = text.substring(open + 1, closeBracket);
                int eq = head.indexOf('=');
                if (eq > 0) {
                    String maybe = head.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
                    if (clickAction(maybe) != null) {
                        actionName = maybe;
                        value = head.substring(eq + 1).trim();
                        contentStart = closeBracket + 1;
                    }
                }
            }
            if (actionName != null && value != null) {
                String closingTag = "[/" + actionName + "]";
                int close = text.indexOf(closingTag, contentStart);
                if (close >= 0) {
                    renderPlain(root, text.substring(i, open));
                    appendClickable(root, text.substring(contentStart, close), actionName, value);
                    i = close + closingTag.length();
                    continue;
                }
            }
            // не тег — '[' обычный символ
            renderPlain(root, text.substring(i, open + 1));
            i = open + 1;
        }
    }

    private static ClickEvent.Action clickAction(String name) {
        // Разрешаем через valueOf + try/catch: если в API конкретного прокси
        // (старые форки Waterfall/FlameCord) нет какого-то действия (например,
        // COPY_TO_CLIPBOARD), НЕ даём упасть с Error — просто без клика.
        // Иначе Error в команде рвёт соединение (dispatchCommand ловит только Exception).
        try {
            switch (name) {
                case "copy":    return ClickEvent.Action.valueOf("COPY_TO_CLIPBOARD");
                case "suggest": return ClickEvent.Action.valueOf("SUGGEST_COMMAND");
                case "run":     return ClickEvent.Action.valueOf("RUN_COMMAND");
                case "url":     return ClickEvent.Action.valueOf("OPEN_URL");
                default:        return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Текст внутри тега рисуем обычными цветовыми прогонами, но каждый с кликом/ховером. */
    private static void appendClickable(TextComponent root, String inner, String actionName, String value) {
        ClickEvent.Action act = clickAction(actionName);
        if (act == null) {
            renderPlain(root, inner);
            return;
        }
        ClickEvent click = null;
        HoverEvent hover = null;
        try {
            click = new ClickEvent(act, value);
        } catch (Throwable ignored) {
            // нет API клика на прокси — рисуем как обычный текст
        }
        try {
            HoverEvent.Action ha = HoverEvent.Action.valueOf("SHOW_TEXT");
            hover = new HoverEvent(ha, hoverText(actionName));
        } catch (Throwable ignored) {
            // нет API ховера — обойдёмся без него
        }
        if (click == null && hover == null) {
            renderPlain(root, inner);
            return;
        }
        parse(root, inner, click, hover);
    }

    /** Авто-подсказка при наведении на кликабельный кусок. */
    private static BaseComponent[] hoverText(String actionName) {
        String msg;
        switch (actionName) {
            case "copy":    msg = "&7Нажми — &fскопировать"; break;
            case "suggest": msg = "&7Нажми — &fвставить в чат"; break;
            case "run":     msg = "&7Нажми — &fвыполнить"; break;
            case "url":     msg = "&7Нажми — &fоткрыть ссылку"; break;
            default:        msg = "";
        }
        return TextComponent.fromLegacyText(legacy(msg));
    }

    private static void renderPlain(TextComponent root, String text) {
        parse(root, text, null, null);
    }

    /** Цветовой разбор строки в «прогоны»; каждый прогон получает click/hover (если заданы). */
    private static void parse(TextComponent root, String text,
                              ClickEvent click, HoverEvent hover) {
        if (text == null || text.isEmpty()) {
            return;
        }
        StringBuilder buf = new StringBuilder();
        net.md_5.bungee.api.ChatColor color = null;
        boolean bold = false, italic = false, underline = false, strike = false, magic = false;

        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '&' || c == '\u00A7') {
                if (i + 1 >= n) {
                    buf.append(c);
                    i++;
                    continue;
                }
                char code = text.charAt(i + 1);
                // hex &#RRGGBB
                if (code == '#' && i + 8 <= n && isHex(text, i + 2, i + 8)) {
                    flushRun(root, buf, color, bold, italic, underline, strike, magic, click, hover);
                    color = hexColor(text.substring(i + 2, i + 8));
                    i += 8;
                    continue;
                }
                // классический &x&R&R&G&G&B&B
                if ((code == 'x' || code == 'X') && i + 14 <= n) {
                    StringBuilder hx = new StringBuilder(6);
                    int p = i + 2;
                    boolean ok = true;
                    for (int k = 0; k < 6; k++) {
                        char pc = text.charAt(p);
                        if ((pc == '&' || pc == '\u00A7') && isHexChar(text.charAt(p + 1))) {
                            hx.append(text.charAt(p + 1));
                            p += 2;
                        } else {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        flushRun(root, buf, color, bold, italic, underline, strike, magic, click, hover);
                        color = hexColor(hx.toString());
                        i = p;
                        continue;
                    }
                }
                char low = Character.toLowerCase(code);
                int ci = "0123456789abcdefklmnor".indexOf(low);
                if (ci >= 0) {
                    flushRun(root, buf, color, bold, italic, underline, strike, magic, click, hover);
                    if (ci < 16) {
                        color = legacyColor(low);
                    } else {
                        switch (low) {
                            case 'k': magic = true; break;
                            case 'l': bold = true; break;
                            case 'm': strike = true; break;
                            case 'n': underline = true; break;
                            case 'o': italic = true; break;
                            default: // 'r' — сброс
                                color = null;
                                bold = italic = underline = strike = magic = false;
                                break;
                        }
                    }
                    i += 2;
                    continue;
                }
                // неизвестный код — оставляем как есть
                buf.append(c);
                i++;
                continue;
            }
            buf.append(c);
            i++;
        }
        flushRun(root, buf, color, bold, italic, underline, strike, magic, click, hover);
    }

    private static void flushRun(TextComponent root, StringBuilder buf,
                                 net.md_5.bungee.api.ChatColor color,
                                 boolean bold, boolean italic, boolean underline,
                                 boolean strike, boolean magic,
                                 ClickEvent click, HoverEvent hover) {
        if (buf.length() == 0) {
            return;
        }
        TextComponent t = new TextComponent(buf.toString());
        t.setColor(color);
        t.setBold(bold);
        t.setItalic(italic);
        t.setUnderlined(underline);
        t.setStrikethrough(strike);
        t.setObfuscated(magic);
        try {
            if (click != null) {
                t.setClickEvent(click);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (hover != null) {
                t.setHoverEvent(hover);
            }
        } catch (Throwable ignored) {
        }
        root.addExtra(t);
        buf.setLength(0);
    }

    private static net.md_5.bungee.api.ChatColor hexColor(String rgb) {
        try {
            return net.md_5.bungee.api.ChatColor.of("#" + rgb);
        } catch (Throwable t) {
            return null; // старый форк без hex — просто без цвета
        }
    }

    private static net.md_5.bungee.api.ChatColor legacyColor(char low) {
        return net.md_5.bungee.api.ChatColor.getByChar(low);
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
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

    /** Отправка игроку или в консоль (у всех CommandSender есть sendMessage(BaseComponent...)). */
    public void sendComp(net.md_5.bungee.api.CommandSender sender, String key, String... args) {
        BaseComponent[] c = compKey(key, args);
        if (c.length > 0) {
            sender.sendMessage(c);
        }
    }

    public void sendCompList(net.md_5.bungee.api.CommandSender sender, String key, String... args) {
        for (String line : rawList(key, args)) {
            BaseComponent[] c = comp(line);
            if (c.length > 0) {
                sender.sendMessage(c);
            }
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
