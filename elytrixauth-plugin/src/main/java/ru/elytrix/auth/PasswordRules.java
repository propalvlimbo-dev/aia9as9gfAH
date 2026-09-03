package ru.elytrix.auth;

/**
 * Правила паролей, общие для /reg и для смены пароля из Telegram-бота.
 * {@link #check} возвращает null, если пароль подходит, иначе код ошибки:
 * too_short / too_long / like_nick / same_chars.
 */
public final class PasswordRules {

    public static final int MAX_LENGTH = 64;

    private PasswordRules() {
    }

    public static String check(PluginConfig cfg, String nickname, String password) {
        if (password == null) {
            return "too_short";
        }
        int min = Math.max(1, cfg.minPassword());
        if (password.length() < min) {
            return "too_short";
        }
        if (password.length() > MAX_LENGTH) {
            return "too_long";
        }
        if (nickname != null && password.equalsIgnoreCase(nickname)) {
            return "like_nick";
        }
        if (password.length() > 1 && allSameChars(password)) {
            return "same_chars";
        }
        return null;
    }

    /** Пароль из одинаковых символов («aaaaaa», «111111») — слишком простой. */
    public static boolean allSameChars(String v) {
        char c = v.charAt(0);
        for (int i = 1; i < v.length(); i++) {
            if (v.charAt(i) != c) {
                return false;
            }
        }
        return true;
    }
}
