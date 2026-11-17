package cn.ks.vms;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Totp {
    static final Set<String> PRIVILEGED_ROLES = Set.of("ADMIN", "ADMINISTRATION", "SAFETY", "EXPORT");
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static String normalize(String secret) {
        String value = secret.replace(" ", "").toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z2-7]{32,64}")) {
            throw Problem.bad("验证器密钥须包含 32 至 64 个 Base32 字符");
        }
        return value;
    }

    static long counter(String secret, String code, Instant now) {
        if (code == null || !code.matches("[0-9]{6}")) {
            return -1;
        }
        long current = now.getEpochSecond() / 30;
        byte[] expected = code.getBytes(StandardCharsets.US_ASCII);
        for (long counter = current; counter >= current - 1; counter--) {
            if (MessageDigest.isEqual(at(secret, counter).getBytes(StandardCharsets.US_ASCII), expected)) {
                return counter;
            }
        }
        return -1;
    }

    static String at(String secret, long counter) {
        try {
            var bytes = new ByteArrayOutputStream();
            int bits = 0;
            int buffer = 0;
            for (char item : secret.toCharArray()) {
                buffer = (buffer << 5) | ALPHABET.indexOf(item);
                bits += 5;
                if (bits >= 8) {
                    bits -= 8;
                    bytes.write((buffer >> bits) & 255);
                }
            }
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(bytes.toByteArray(), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = hash[hash.length - 1] & 15;
            int value = ByteBuffer.wrap(hash, offset, Integer.BYTES).getInt() & Integer.MAX_VALUE;
            return String.format(Locale.ROOT, "%06d", value % 1_000_000);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Cannot initialize TOTP validator", error);
        }
    }
}
