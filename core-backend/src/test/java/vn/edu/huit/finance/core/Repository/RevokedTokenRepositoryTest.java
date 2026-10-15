package vn.edu.huit.finance.core.Repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import vn.edu.huit.finance.core.Entity.RevokedToken;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real mapping and queries on H2: the unique hash and the bulk purge are checked for real. */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
class RevokedTokenRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 3, 0);
    private static final String HASH_A = "a".repeat(RevokedToken.TOKEN_HASH_LENGTH);
    private static final String HASH_B = "b".repeat(RevokedToken.TOKEN_HASH_LENGTH);
    private static final String HASH_C = "c".repeat(RevokedToken.TOKEN_HASH_LENGTH);

    @Autowired private RevokedTokenRepository revokedTokenRepository;
    @Autowired private TestEntityManager entityManager;

    private static RevokedToken revoked(String hash, LocalDateTime expiresAt) {
        return new RevokedToken(hash, expiresAt, NOW.minusMinutes(5));
    }

    @Test
    void existsByTokenHash_isTrueForARevokedTokenAndOnlyForThat() {
        revokedTokenRepository.saveAndFlush(revoked(HASH_A, NOW.plusMinutes(10)));

        assertTrue(revokedTokenRepository.existsByTokenHash(HASH_A));
        assertFalse(revokedTokenRepository.existsByTokenHash(HASH_B));
    }

    @Test
    void theSameTokenCannotBeStoredTwice() {
        revokedTokenRepository.saveAndFlush(revoked(HASH_A, NOW.plusMinutes(10)));

        assertThrows(DataIntegrityViolationException.class,
                () -> revokedTokenRepository.saveAndFlush(revoked(HASH_A, NOW.plusMinutes(20))));
    }

    @Test
    void deleteExpiredBefore_removesOnlyTheTokensThatExpiredBeforeThatMoment() {
        revokedTokenRepository.saveAndFlush(revoked(HASH_A, NOW.minusHours(1)));   // expired: goes
        revokedTokenRepository.saveAndFlush(revoked(HASH_B, NOW));                 // expires exactly now: stays
        revokedTokenRepository.saveAndFlush(revoked(HASH_C, NOW.plusHours(1)));    // still valid: stays

        int removed = revokedTokenRepository.deleteExpiredBefore(NOW);
        entityManager.clear();

        assertEquals(1, removed);
        List<String> left = revokedTokenRepository.findAll().stream().map(RevokedToken::getTokenHash).sorted().toList();
        assertEquals(List.of(HASH_B, HASH_C), left);
    }

    @Test
    void deleteExpiredBefore_onAnEmptyTableRemovesNothing() {
        assertEquals(0, revokedTokenRepository.deleteExpiredBefore(NOW));
    }
}
