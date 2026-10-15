package vn.edu.huit.finance.core.Service.Audit;

import vn.edu.huit.finance.core.Entity.User;

import java.util.Objects;

/**
 * Who an action_logs row says acted. There is deliberately no way to build one from a bare user id or email: an account
 * is an actor only as the {@link User} the server loaded for the caller (through CurrentUserProvider, i.e. the JWT or the
 * admin session), never as an id or email taken from a request body or query parameter.
 */
public final class AuditActor {

    /** What a row shows as the actor of something the application did on its own: a scheduled job, a payment webhook. */
    public static final String SYSTEM_LABEL = "HỆ THỐNG";
    /** What a row shows for a caller that was not signed in and claimed no identity. */
    public static final String ANONYMOUS_LABEL = "ẨN DANH";

    private enum Kind { ACCOUNT, SYSTEM, UNAUTHENTICATED, CURRENT_CALLER }

    private static final AuditActor SYSTEM = new AuditActor(Kind.SYSTEM, null, SYSTEM_LABEL);
    private static final AuditActor CURRENT_CALLER = new AuditActor(Kind.CURRENT_CALLER, null, null);

    private final Kind kind;
    private final Long userId;
    private final String label;

    private AuditActor(Kind kind, Long userId, String label) {
        this.kind = kind;
        this.userId = userId;
        this.label = label;
    }

    /** An account the server resolved itself: the caller of a request, or the owner of an order a webhook settles. */
    public static AuditActor of(User user) {
        Objects.requireNonNull(user, "user");
        return new AuditActor(Kind.ACCOUNT, user.getId(), user.getEmail());
    }

    public static AuditActor system() {
        return SYSTEM;
    }

    /**
     * A caller that is not signed in: a failed sign-in, a refused token, an OTP request. {@code claimedIdentity} (the
     * email typed in, the subject of a refused token) is only a label; no account id is ever taken from it.
     */
    public static AuditActor unauthenticated(String claimedIdentity) {
        boolean claimsNothing = claimedIdentity == null || claimedIdentity.isBlank();
        return new AuditActor(Kind.UNAUTHENTICATED, null, claimsNothing ? ANONYMOUS_LABEL : claimedIdentity);
    }

    /**
     * The authenticated caller of the request the row is written in, looked up when the row is written. For code that
     * has no account at hand (a security filter, an exception handler); one that is not signed in is recorded as
     * {@link #unauthenticated}.
     */
    public static AuditActor currentCaller() {
        return CURRENT_CALLER;
    }

    public boolean isCurrentCaller() {
        return kind == Kind.CURRENT_CALLER;
    }

    /** The account that acted; null for the system and for a caller that was not signed in. */
    public Long userId() {
        return userId;
    }

    /** The email of the account, the claimed identity, or the system's label. */
    public String label() {
        return label;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AuditActor actor
                && kind == actor.kind && Objects.equals(userId, actor.userId) && Objects.equals(label, actor.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, userId, label);
    }

    @Override
    public String toString() {
        return "AuditActor[" + kind + ", userId=" + userId + "]";
    }
}
