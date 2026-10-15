package vn.edu.huit.finance.core.Security;

import org.springframework.http.HttpStatus;

/**
 * Why an API request has no authenticated caller. JwtAuthFilter never answers a request itself: it records the reason
 * for a bearer token it could not use under {@link #REQUEST_ATTRIBUTE} and lets the request go on anonymously. A public
 * endpoint then serves it like any anonymous request; on a protected one the security rules hand it to
 * {@link JsonAuthenticationEntryPoint}, which answers with the status and message of the reason.
 */
public enum TokenRejection {

    /** No bearer token at all: the default when the filter recorded nothing. */
    MISSING(HttpStatus.UNAUTHORIZED, "Thiếu token xác thực"),
    // The app renews the session on every 401 below; 403 would not tell it to.
    EXPIRED(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."),
    /** Bad signature, not a JWT, a refresh token, no type or no credentials version, or an account that is gone. */
    INVALID(HttpStatus.UNAUTHORIZED, "Token xác thực không hợp lệ. Vui lòng đăng nhập lại."),
    REVOKED(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã bị thu hồi. Vui lòng đăng nhập lại."),
    /** Issued before the password was last changed or reset, maybe on another device. */
    OUTDATED(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hiệu lực. Vui lòng đăng nhập lại."),
    /** The token is fine but the account may not use the API: 403, as before. */
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Tài khoản đã bị khóa.");

    public static final String REQUEST_ATTRIBUTE = TokenRejection.class.getName();

    private final HttpStatus status;
    private final String message;

    TokenRejection(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
