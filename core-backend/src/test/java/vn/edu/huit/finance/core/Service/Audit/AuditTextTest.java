package vn.edu.huit.finance.core.Service.Audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import vn.edu.huit.finance.core.Security.JwtUtil;

import static org.junit.jupiter.api.Assertions.*;

/** What any text becomes before it is stored in action_logs. */
class AuditTextTest {

    private static final int MAX = 40;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\r\n\t"})
    void nothingOrOnlyBlanksIsStoredAsNull(String text) {
        assertNull(AuditText.clean(text, MAX));
    }

    @Test
    void aLineBreakCannotForgeALineOfItsOwn() {
        String forged = "alice@example.com\r\n2026-10-01 LOGIN_SUCCESS admin@example.com x\u0085y";

        String cleaned = AuditText.clean(forged, 200);

        assertFalse(cleaned.contains("\r"));
        assertFalse(cleaned.contains("\n"));
        assertFalse(cleaned.contains(" "));
        assertFalse(cleaned.contains("\u0085"));
        assertEquals("alice@example.com 2026-10-01 LOGIN_SUCCESS admin@example.com x y", cleaned);
    }

    @Test
    void aTokenThatSlippedIntoATextIsReplaced_whateverItsKind() {
        JwtUtil jwtUtil = new JwtUtil();
        String access = jwtUtil.generateToken("alice@example.com", 3);
        String refresh = jwtUtil.generateRefreshToken("alice@example.com", 3);

        String cleaned = AuditText.clean("access " + access + " refresh " + refresh + " header Bearer abc.def", 2000);

        assertFalse(cleaned.contains(access));
        assertFalse(cleaned.contains(refresh));
        assertFalse(cleaned.contains("abc.def"));
        assertEquals("access [REDACTED] refresh [REDACTED] header [REDACTED]", cleaned);
    }

    @Test
    void aPasswordHashThatSlippedIntoATextIsReplaced() {
        String hash = new BCryptPasswordEncoder(4).encode("OldPass123");

        assertEquals("hash [REDACTED] end", AuditText.clean("hash " + hash + " end", 200));
    }

    @Test
    void aTextLongerThanItsColumnIsCutWithAnEllipsis_andNeverLonger() {
        String cleaned = AuditText.clean("x".repeat(500), MAX);

        assertEquals(MAX, cleaned.length());
        assertTrue(cleaned.endsWith("…"));
    }

    @Test
    void aCutNeverSplitsACharacterOutsideTheBasicPlane() {
        // 38 characters, then an emoji (two UTF-16 units) across the cut.
        String text = "a".repeat(MAX - 2) + "😀" + "tail";

        String cleaned = AuditText.clean(text, MAX);

        assertTrue(cleaned.length() <= MAX);
        assertFalse(Character.isHighSurrogate(cleaned.charAt(cleaned.length() - 2)), "no half of a surrogate pair is kept");
        assertTrue(cleaned.endsWith("…"));
    }

    @Test
    void anOrdinaryTextIsKeptAsItIs() {
        String text = "Tạo giao dịch chi 150000 VNĐ, danh mục #3, của người dùng #7";

        assertEquals(text, AuditText.clean(text, 1000));
    }
}
