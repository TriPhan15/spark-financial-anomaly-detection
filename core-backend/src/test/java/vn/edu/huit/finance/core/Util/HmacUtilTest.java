package vn.edu.huit.finance.core.Util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HmacUtilTest {

    private static final String SECRET = "test_secret";

    @Test
    void sha256_deterministic() {
        String a = HmacUtil.hmacSha256("orderX|1000", SECRET);
        String b = HmacUtil.hmacSha256("orderX|1000", SECRET);
        assertEquals(a, b);
        assertEquals(64, a.length()); // SHA256 = 32 byte = 64 hex
    }

    @Test
    void sha512_longer_and_different() {
        String s256 = HmacUtil.hmacSha256("data", SECRET);
        String s512 = HmacUtil.hmacSha512("data", SECRET);
        assertEquals(128, s512.length()); // SHA512 = 64 byte = 128 hex
        assertNotEquals(s256, s512);
    }

    @Test
    void differentSecret_differentSignature() {
        assertNotEquals(
                HmacUtil.hmacSha256("data", "k1"),
                HmacUtil.hmacSha256("data", "k2"));
    }

    @Test
    void verify_true_for_validSignature() {
        String sig = HmacUtil.hmacSha256("payload", SECRET);
        assertTrue(HmacUtil.verify("payload", SECRET, sig));
    }

    @Test
    void verify_false_for_tampered() {
        String sig = HmacUtil.hmacSha256("payload", SECRET);
        assertFalse(HmacUtil.verify("payload-tampered", SECRET, sig));
        assertFalse(HmacUtil.verify("payload", SECRET, "deadbeef"));
        assertFalse(HmacUtil.verify("payload", SECRET, null));
    }
}
