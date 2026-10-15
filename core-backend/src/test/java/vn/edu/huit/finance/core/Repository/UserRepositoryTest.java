package vn.edu.huit.finance.core.Repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import vn.edu.huit.finance.core.Entity.User;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one write of a password change or reset on H2: password hash and credentials version in one UPDATE, guarded by the
 * version the caller expects, and neither column written by a save of the entity.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
class UserRepositoryTest {

    private static final String OLD_HASH = "$2a$04$oldoldoldoldoldoldoldoldoldoldoldoldoldoldoldoldoldol";
    private static final String NEW_HASH = "$2a$04$newnewnewnewnewnewnewnewnewnewnewnewnewnewnewnewnewne";
    private static final int VERSION = 3;

    @Autowired private UserRepository userRepository;
    @Autowired private TestEntityManager entityManager;

    private Long aliceId;
    private Long bobId;

    @BeforeEach
    void setUp() {
        aliceId = persist("alice", "0900000001").getId();
        bobId = persist("bob", "0900000002").getId();
        entityManager.flush();
        entityManager.clear();
    }

    private User persist(String name, String phone) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@example.com");
        user.setPhone(phone);
        user.setPassword(OLD_HASH);
        user.setCredentialsVersion(VERSION);
        return entityManager.persist(user);
    }

    /** The row as the database has it now, not as the persistence context remembers it. */
    private User reread(Long id) {
        entityManager.clear();
        return userRepository.findById(id).orElseThrow();
    }

    @Test
    void theNewUserStartsAtTheVersionItWasInsertedWith() {
        // Insertable, although never updatable through the entity.
        assertEquals(VERSION, reread(aliceId).getCredentialsVersion());
        assertEquals(OLD_HASH, reread(aliceId).getPassword());
    }

    @Test
    void updatePasswordAndEndSessions_writesTheHashAndRaisesTheVersionByOne_whenTheExpectedVersionMatches() {
        int updated = userRepository.updatePasswordAndEndSessions(aliceId, NEW_HASH, VERSION);

        assertEquals(1, updated);
        User alice = reread(aliceId);
        assertEquals(NEW_HASH, alice.getPassword());
        assertEquals(VERSION + 1, alice.getCredentialsVersion());
        // Only that row.
        User bob = reread(bobId);
        assertEquals(OLD_HASH, bob.getPassword());
        assertEquals(VERSION, bob.getCredentialsVersion());
    }

    @Test
    void updatePasswordAndEndSessions_writesNothing_whenTheVersionHasMovedOnSinceTheCallerReadIt() {
        userRepository.updatePasswordAndEndSessions(aliceId, NEW_HASH, VERSION);

        // A second change that still expects the old version must not be applied on top of the first.
        int updated = userRepository.updatePasswordAndEndSessions(aliceId, OLD_HASH, VERSION);

        assertEquals(0, updated);
        User alice = reread(aliceId);
        assertEquals(NEW_HASH, alice.getPassword());
        assertEquals(VERSION + 1, alice.getCredentialsVersion());
    }

    @Test
    void updatePasswordAndEndSessions_writesNothing_forAUserThatDoesNotExist() {
        assertEquals(0, userRepository.updatePasswordAndEndSessions(bobId + 1000, NEW_HASH, VERSION));
    }

    @Test
    void savingTheEntity_neverWritesThePasswordOrTheVersion_evenWhenTheyWereChangedOnIt() {
        User alice = userRepository.findById(aliceId).orElseThrow();
        alice.setPassword(NEW_HASH);
        alice.setCredentialsVersion(VERSION + 5);
        alice.setWalletBalance(new BigDecimal("1000.00"));
        userRepository.saveAndFlush(alice);

        User stored = reread(aliceId);
        assertEquals(OLD_HASH, stored.getPassword());
        assertEquals(VERSION, stored.getCredentialsVersion());
        assertEquals(0, new BigDecimal("1000.00").compareTo(stored.getWalletBalance()), "the other columns are written");
    }
}
