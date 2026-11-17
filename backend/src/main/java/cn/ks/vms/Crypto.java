package cn.ks.vms;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class Crypto {
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    Crypto(@Value("${vms.secret}") String secret, @Value("${vms.demo}") boolean demo) {
        if (!demo && secret.length() < 32) {
            throw new IllegalStateException("VMS_SECRET must contain at least 32 random characters");
        }
        String value = secret.isBlank() ? "ks-vms-demo-key-for-fictional-data-only" : secret;
        key = new SecretKeySpec(value.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    String digest(String scope, String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal((scope + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Cannot initialize keyed hash", error);
        }
    }

    String token() {
        var bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String code() {
        return String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    String encryptAuthenticator(String input) {
        return encrypt("authenticator-v1", input);
    }

    String decryptAuthenticator(String data) {
        return decrypt("authenticator-v1", data);
    }

    String encrypt(String scope, String input) {
        var nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, scope, nonce, input.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length)
            .put(nonce).put(ciphertext).array());
    }

    String decrypt(String scope, String data) {
        byte[] encoded = Base64.getDecoder().decode(data);
        return new String(cipher(Cipher.DECRYPT_MODE, scope, Arrays.copyOf(encoded, 12),
            Arrays.copyOfRange(encoded, 12, encoded.length)), StandardCharsets.UTF_8);
    }

    private byte[] cipher(int mode, String scope, byte[] nonce, byte[] input) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            var key = new SecretKeySpec(HexFormat.of().parseHex(digest("derive", scope)), "AES");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Cannot process authenticator secret", error);
        }
    }
}
