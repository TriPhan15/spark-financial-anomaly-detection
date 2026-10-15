package vn.edu.huit.finance.core.Service.Audit;

import java.util.regex.Pattern;

/**
 * Makes a text fit to be stored in action_logs. It becomes one line (control characters, CR and LF included, turn into
 * spaces, so a value cannot forge a line of its own in a log export); a token or a password hash in it is replaced, as a
 * second line of defence (the callers never pass one); and it is cut to its column, so that a long value can never make
 * the insert fail, which would roll back the change the row records.
 */
public final class AuditText {

    /** What replaces a credential found in a text. */
    public static final String REDACTED = "[REDACTED]";
    private static final String ELLIPSIS = "…";

    // Control characters (C0 and C1) and the Unicode line and paragraph separators.
    private static final Pattern LINE_BREAKERS = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]+");
    // "Bearer <anything>" first, so that a whole Authorization header value goes at once.
    private static final Pattern BEARER_CREDENTIAL = Pattern.compile("(?i)\\bbearer\\s+\\S+");
    // A JWT: three base64url parts, the first one a JSON header ("eyJ" is the encoding of '{"').
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Pattern BCRYPT_HASH = Pattern.compile("\\$2[abxy]?\\$\\d{2}\\$[./A-Za-z0-9]{53}");

    private AuditText() {
    }

    /** The text as it may be stored, at most {@code maxLength} characters; null for null or a text that is only blanks. */
    public static String clean(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String oneLine = LINE_BREAKERS.matcher(text).replaceAll(" ").strip();
        String withoutBearer = BEARER_CREDENTIAL.matcher(oneLine).replaceAll(REDACTED);
        String withoutJwt = JWT.matcher(withoutBearer).replaceAll(REDACTED);
        String redacted = BCRYPT_HASH.matcher(withoutJwt).replaceAll(REDACTED);
        return redacted.isEmpty() ? null : truncate(redacted, maxLength);
    }

    /** Cut with an ellipsis, never between the two halves of a character outside the Basic Multilingual Plane. */
    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int end = maxLength - ELLIPSIS.length();
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + ELLIPSIS;
    }
}
