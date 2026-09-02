package ru.elytrix.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Хранение паролей: PBKDF2-HMAC-SHA256, 210 000 итераций, соль 16 байт.
 * Формат: pbkdf2_sha256$210000$<salt_b64>$<hash_b64>
 * (никакого голого SHA-256, как в референсном mcauth).
 */
public final class PasswordHash {

    private static final String ALGO = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private PasswordHash() {
    }

    public static String create(String password) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS, KEY_BITS / 8);
        return "pbkdf2_sha256$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean verify(String password, String stored) {
        if (stored == null || !stored.startsWith("pbkdf2_sha256$")) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password, salt, iterations, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations, int len) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, len * 8);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGO);
            try {
                return factory.generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 недоступен", e);
        }
    }
}
