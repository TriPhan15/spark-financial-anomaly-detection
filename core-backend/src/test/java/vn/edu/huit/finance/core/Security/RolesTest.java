package vn.edu.huit.finance.core.Security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.huit.finance.core.Exception.InvalidRoleException;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class RolesTest {

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "admin", "Admin", "  admin  ", "ROLE_ADMIN", "role_admin", " Role_Admin\t"})
    void everySpellingOfAdminBecomesRoleAdmin(String spelling) {
        assertEquals("ROLE_ADMIN", Roles.normalize(spelling));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "user", "ROLE_USER", " role_user "})
    void everySpellingOfUserBecomesRoleUser(String spelling) {
        assertEquals("ROLE_USER", Roles.normalize(spelling));
    }

    @Test
    void aRoleThatIsAlreadyCanonicalIsLeftAlone() {
        assertEquals(Roles.ADMIN, Roles.normalize(Roles.ADMIN));
        assertEquals(Roles.USER, Roles.normalize(Roles.USER));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "ROLE_", " role_ ", "ROLE_  "})
    void aRoleWithoutAnyNameIsRefused(String nothing) {
        assertThrows(InvalidRoleException.class, () -> Roles.normalize(nothing));
    }

    @Test
    void theSpellingDoesNotDependOnTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR")); // where "admin".toUpperCase() would give "ADMİN"
            assertEquals("ROLE_ADMIN", Roles.normalize("admin"));
            assertTrue(Roles.isAdmin("admin"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ADMIN", "admin", " role_admin "})
    void isAdminRecognisesEverySpellingOfAdmin(String spelling) {
        assertTrue(Roles.isAdmin(spelling));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "ROLE_USER", "USER", "ROLE_LOCKED", "ROLE_ADMINISTRATOR", "ADMINISTRATOR", "ROLE_SUPERADMIN"})
    void isAdminIsFalseForEverythingElse(String role) {
        assertFalse(Roles.isAdmin(role));
    }

    @Test
    void theExpressionForPreAuthorizeNamesTheAdminRole() {
        assertEquals("hasRole('ROLE_ADMIN')", Roles.HAS_ADMIN);
    }
}
