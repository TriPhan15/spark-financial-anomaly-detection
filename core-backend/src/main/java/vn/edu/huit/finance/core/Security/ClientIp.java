package vn.edu.huit.finance.core.Security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.regex.Pattern;

/**
 * The address of the client of a request, the one place that reads it (sign-in lockout and audit log alike). Behind the
 * Nginx reverse proxy of the deployment, the first entry of X-Forwarded-For is the client; without the header, the
 * address of the connection is. Only an IP literal is taken: anything else in the header (a host name, "unknown", text
 * of any length) falls back to the connection's address, so the value always fits the audit column and can never be
 * free text chosen by the client.
 * <p>
 * Trust limit: the header is set by whoever sends the request. It names the client only if every request comes through
 * the proxy and the proxy overwrites the header; a client that reaches the application directly, or a proxy that only
 * appends to the header, lets the client choose this value.
 */
public final class ClientIp {

    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private static final int MAX_LENGTH = 45;
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3}\\.){3}\\d{1,3}");
    // Hex groups and colons, optionally ending in a dotted IPv4 part (::ffff:192.0.2.1); at least one colon.
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f]{0,4}(:[0-9A-Fa-f]{0,4}){1,7}(:(\\d{1,3}\\.){3}\\d{1,3})?");

    private ClientIp() {
    }

    /** The client's address, or null when the request carries no usable one. */
    public static String of(HttpServletRequest request) {
        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwardedFor != null) {
            String first = forwardedFor.split(",", 2)[0].trim();
            if (isIpLiteral(first)) {
                return first;
            }
        }
        String remoteAddress = request.getRemoteAddr();
        return isIpLiteral(remoteAddress) ? remoteAddress : null;
    }

    /** The client's address of the request this thread is serving; null outside of one (a scheduled job). */
    public static String ofCurrentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return of(servletAttributes.getRequest());
        }
        return null;
    }

    static boolean isIpLiteral(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_LENGTH
                && (IPV4.matcher(value).matches() || IPV6.matcher(value).matches());
    }
}
