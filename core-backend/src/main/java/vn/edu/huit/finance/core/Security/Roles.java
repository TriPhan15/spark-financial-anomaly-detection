package vn.edu.huit.finance.core.Security;

import vn.edu.huit.finance.core.Exception.InvalidRoleException;

import java.util.Locale;

/**
 * The roles of the application and how they are written. A role is stored and granted as "ROLE_&lt;NAME&gt;" in upper
 * case, which is what Spring Security's hasRole/hasAuthority compare with, while forms and clients may send "admin",
 * "ADMIN" or "ROLE_ADMIN". Every write and every comparison goes through here so the two spellings cannot disagree again.
 */
public final class Roles {

    public static final String PREFIX = "ROLE_";
    public static final String USER = PREFIX + "USER";
    public static final String ADMIN = PREFIX + "ADMIN";
    /** Given to an account an administrator has locked: it can no longer sign in or renew its session. */
    public static final String LOCKED = PREFIX + "LOCKED";

    /** For @PreAuthorize, which needs a compile-time constant. */
    public static final String HAS_ADMIN = "hasRole('" + ADMIN + "')";

    private static final String MSG_ROLE_REQUIRED = "Vai trò không được để trống";

    private Roles() {
    }

    /** "admin", " ADMIN " and "role_admin" all become "ROLE_ADMIN". */
    public static String normalize(String role) {
        if (role == null || role.isBlank()) {
            throw new InvalidRoleException(MSG_ROLE_REQUIRED);
        }
        // Locale.ROOT: in a Turkish default locale "admin".toUpperCase() would contain a dotted capital I.
        String upperCase = role.trim().toUpperCase(Locale.ROOT);
        String canonical = upperCase.startsWith(PREFIX) ? upperCase : PREFIX + upperCase;
        if (canonical.length() == PREFIX.length()) {
            throw new InvalidRoleException(MSG_ROLE_REQUIRED);
        }
        return canonical;
    }

    /** True for "ROLE_ADMIN" and for every spelling {@link #normalize} maps to it; false for a missing or blank role. */
    public static boolean isAdmin(String role) {
        return role != null && !role.isBlank() && ADMIN.equals(normalize(role));
    }
}
