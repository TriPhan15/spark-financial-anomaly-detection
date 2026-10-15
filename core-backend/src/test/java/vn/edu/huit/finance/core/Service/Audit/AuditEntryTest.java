package vn.edu.huit.finance.core.Service.Audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Who an audit row names, and what an entry may say. */
class AuditEntryTest {

    private static User alice() {
        User user = new User();
        user.setId(7L);
        user.setEmail("alice@example.com");
        return user;
    }

    @Test
    void anAccountActorIsTheServerLoadedUser_itsIdAndItsEmail() {
        AuditActor actor = AuditActor.of(alice());

        assertEquals(7L, actor.userId());
        assertEquals("alice@example.com", actor.label());
        assertFalse(actor.isCurrentCaller());
    }

    @Test
    void noActorCanBeBuiltFromABareIdOrEmail_onlyFromAUserTheServerLoaded() {
        Constructor<?>[] constructors = AuditActor.class.getDeclaredConstructors();

        assertTrue(Arrays.stream(constructors).allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }

    @Test
    void theSystemHasNoAccount() {
        assertNull(AuditActor.system().userId());
        assertEquals(AuditActor.SYSTEM_LABEL, AuditActor.system().label());
    }

    @Test
    void aCallerThatIsNotSignedInIsNamedOnlyByWhatItClaims_neverWithAnAccount() {
        AuditActor claimed = AuditActor.unauthenticated("mallory@example.com");

        assertNull(claimed.userId());
        assertEquals("mallory@example.com", claimed.label());
        assertNotEquals(AuditActor.of(alice()), AuditActor.unauthenticated("alice@example.com"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void aCallerThatClaimsNothingIsAnonymous(String claimed) {
        assertEquals(AuditActor.ANONYMOUS_LABEL, AuditActor.unauthenticated(claimed).label());
    }

    @Test
    void theCurrentCallerIsLookedUpLater_andIsNobodyYet() {
        AuditActor caller = AuditActor.currentCaller();

        assertTrue(caller.isCurrentCaller());
        assertNull(caller.userId());
        assertNull(caller.label());
    }

    @Test
    void anEntryNeedsAnActionAndAnActor() {
        assertThrows(NullPointerException.class, () -> AuditEntry.of(null, AuditActor.system(), 1L, "x"));
        assertThrows(NullPointerException.class, () -> AuditEntry.of(AuditAction.USER_UPDATED, null, 1L, "x"));
    }

    @Test
    void anActionAboutNoSingleRecordTakesNoTargetId() {
        assertThrows(IllegalArgumentException.class,
                () -> AuditEntry.of(AuditAction.AUTH_ACCESS_DENIED, AuditActor.currentCaller(), 5L, "GET /api/admin"));
        assertDoesNotThrow(() -> AuditEntry.of(AuditAction.AUTH_ACCESS_DENIED, AuditActor.currentCaller(), null, "GET /api/admin"));
    }

    @Test
    void aSecurityEventCarriesItsEntry() {
        SecurityAuditEvent event = SecurityAuditEvent.accessDenied("Phân quyền theo vai trò", "DELETE", "/api/admin/receipts/3");

        assertEquals(AuditAction.AUTH_ACCESS_DENIED, event.entry().action());
        assertTrue(event.entry().actor().isCurrentCaller());
        assertNull(event.entry().targetId());
        assertEquals("Phân quyền theo vai trò: DELETE /api/admin/receipts/3", event.entry().description());
    }
}
