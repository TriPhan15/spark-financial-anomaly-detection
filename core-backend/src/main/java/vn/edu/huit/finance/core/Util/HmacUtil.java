package vn.edu.huit.finance.core.Util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

public final class HmacUtil {

    private HmacUtil() {}

    public static String hmacSha256(String data, String secret) {
        return hmac("HmacSHA256", data, secret);
    }

    public static String hmacSha512(String data, String secret) {
        return hmac("HmacSHA512", data, secret);
    }

    private static String hmac(String algo, String data, String secret) {
        try {
            Mac mac = Mac.getInstance(algo);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algo));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Loi tao HMAC: " + e.getMessage(), e);
        }
    }

    public static boolean verify(String data, String secret, String signature) {
        if (signature == null) return false;
        String expected = hmacSha256(data, secret);
        if (expected.length() != signature.length()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ signature.charAt(i);
        }
        return diff == 0;
    }
}
