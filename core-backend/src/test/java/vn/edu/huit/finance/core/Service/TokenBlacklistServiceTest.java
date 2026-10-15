package vn.edu.huit.finance.core.Service;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Entity.RevokedToken;
import vn.edu.huit.finance.core.Repository.RevokedTokenRepository;
import vn.edu.huit.finance.core.Security.JwtUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    private static final String TOKEN = "abc";
    // SHA-256 of "abc", the standard test vector: this is what has to be stored, and never the token itself.
    private static final String TOKEN_HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final Instant NOW = Instant.parse("2026-09-21T03:00:00Z");
    private static final Instant EXPIRY = NOW.plus(Duration.ofMinutes(10));

    @Mock private RevokedTokenRepository revokedTokenRepository;
    @Mock private JwtUtil jwtUtil;

    private TokenBlacklistService service;

    @BeforeEach
    void setUp() {
        service = new TokenBlacklistService();
        ReflectionTestUtils.setField(service, "revokedTokenRepository", revokedTokenRepository);
        ReflectionTestUtils.setField(service, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- revoke

    @Test
    void revoke_storesTheHashOfTheToken_theMomentItExpires_andWhenItWasRevoked() {
        when(jwtUtil.extractExpiration(TOKEN)).thenReturn(Date.from(EXPIRY));

        service.revoke(TOKEN);

        ArgumentCaptor<RevokedToken> saved = ArgumentCaptor.forClass(RevokedToken.class);
        verify(revokedTokenRepository).save(saved.capture());
        assertEquals(TOKEN_HASH, saved.getValue().getTokenHash());
        assertNotEquals(TOKEN, saved.getValue().getTokenHash(), "the token itself must never be stored");
        assertEquals(LocalDateTime.of(2026, 9, 21, 3, 10), saved.getValue().getExpiresAt());
        assertEquals(LocalDateTime.of(2026, 9, 21, 3, 0), saved.getValue().getRevokedAt());
    }

    @Test
    void revoke_twice_storesItOnlyOnce() {
        when(jwtUtil.extractExpiration(TOKEN)).thenReturn(Date.from(EXPIRY));
        when(revokedTokenRepository.existsByTokenHash(TOKEN_HASH)).thenReturn(true);

        service.revoke(TOKEN);

        verify(revokedTokenRepository, never()).save(any());
    }

    @Test
    void revoke_skipsATokenThatExpiredWhileItWasBeingRevoked_becauseItCannotBeUsedAnyway() {
        when(jwtUtil.extractExpiration(TOKEN)).thenThrow(new ExpiredJwtException(null, null, "expired"));

        assertDoesNotThrow(() -> service.revoke(TOKEN));

        verifyNoInteractions(revokedTokenRepository);
    }

    @Test
    void revoke_doesNotSwallowOtherTokenFailures_becauseItIsOnlyForTokensThatWereVerified() {
        when(jwtUtil.extractExpiration(TOKEN)).thenThrow(new MalformedJwtException("garbage"));

        assertThrows(MalformedJwtException.class, () -> service.revoke(TOKEN));

        verifyNoInteractions(revokedTokenRepository);
    }

    @Test
    void revoke_doesNotSwallowADatabaseFailure_soTheCallersTransactionCanRollBack() {
        when(jwtUtil.extractExpiration(TOKEN)).thenReturn(Date.from(EXPIRY));
        doThrow(new IllegalStateException("database went away")).when(revokedTokenRepository).save(any(RevokedToken.class));

        assertThrows(IllegalStateException.class, () -> service.revoke(TOKEN));
    }

    // --------------------------------------------------------------------------------------------------- revokeIfValid

    @Test
    void revokeIfValid_revokesAValidToken() {
        when(jwtUtil.validateToken(TOKEN)).thenReturn(true);
        when(jwtUtil.extractExpiration(TOKEN)).thenReturn(Date.from(EXPIRY));

        service.revokeIfValid(TOKEN);

        verify(revokedTokenRepository).save(any(RevokedToken.class));
    }

    @Test
    void revokeIfValid_skipsAnInvalidOrExpiredToken_withoutFailing() {
        when(jwtUtil.validateToken(TOKEN)).thenReturn(false);

        assertDoesNotThrow(() -> service.revokeIfValid(TOKEN));

        verify(jwtUtil, never()).extractExpiration(any());
        verifyNoInteractions(revokedTokenRepository);
    }

    // ------------------------------------------------------------------------------------------------------- isRevoked

    @Test
    void isRevoked_asksForTheHashOfTheToken() {
        when(revokedTokenRepository.existsByTokenHash(TOKEN_HASH)).thenReturn(true);

        assertTrue(service.isRevoked(TOKEN));
    }

    @Test
    void isRevoked_isFalseForATokenThatWasNeverRevoked() {
        assertFalse(service.isRevoked("some.other.token"));

        verify(revokedTokenRepository).existsByTokenHash(sha256("some.other.token"));
    }

    // ------------------------------------------------------------------------------------------------------ purgeExpired

    @Test
    void purgeExpired_deletesWhatExpiredBeforeNow() {
        when(revokedTokenRepository.deleteExpiredBefore(LocalDateTime.of(2026, 9, 21, 3, 0))).thenReturn(3);

        service.purgeExpired();

        verify(revokedTokenRepository).deleteExpiredBefore(LocalDateTime.of(2026, 9, 21, 3, 0));
    }

    // ---------------------------------------------------------------------------------------------- structural guards

    @Test
    void theWritersRunInAWritableTransaction_andTheLookupInAReadOnlyOne() throws NoSuchMethodException {
        for (String writer : new String[] {"revoke", "revokeIfValid", "purgeExpired"}) {
            Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(
                    method(writer), Transactional.class);
            assertNotNull(transactional, writer + " must be @Transactional");
            assertFalse(transactional.readOnly(), writer + " writes, so it cannot be read-only");
        }

        Transactional lookup = AnnotatedElementUtils.findMergedAnnotation(method("isRevoked"), Transactional.class);
        assertNotNull(lookup);
        assertTrue(lookup.readOnly());
    }

    @Test
    void thePurgeIsScheduled() throws NoSuchMethodException {
        Scheduled scheduled = AnnotatedElementUtils.findMergedAnnotation(method("purgeExpired"), Scheduled.class);

        assertNotNull(scheduled, "expired rows have to be removed, or the table grows for ever");
        assertFalse(scheduled.cron().isBlank());
    }

    private static java.lang.reflect.Method method(String name) throws NoSuchMethodException {
        for (java.lang.reflect.Method candidate : TokenBlacklistService.class.getMethods()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new NoSuchMethodException(name);
    }
}
