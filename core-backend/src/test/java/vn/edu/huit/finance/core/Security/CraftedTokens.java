package vn.edu.huit.finance.core.Security;

import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

/**
 * Tokens JwtUtil never issues, for the checks that have to refuse them: the format of the releases before credentials
 * versions and token types, a token that lacks one of the two, one that has expired and one signed with another key.
 * Apart from the last, they are signed with the service's own key, so only the missing or wrong part tells them apart.
 */
public final class CraftedTokens {

    private static final Duration LIFETIME = Duration.ofMinutes(15);
    // Long enough for HS256 (256 bits at least), and not the service's secret.
    private static final String OTHER_SECRET = "NotTheSecretOfThisServiceButLongEnoughForHs256!";

    private CraftedTokens() {
    }

    /** Exactly as the release before credentials versions issued them: same key, subject, id and lifetime; no type, no version. */
    public static String legacy(String subject) {
        return signed(subject, serviceKey(), 0).compact();
    }

    /** Carries a credentials version but no type in its header. */
    public static String withoutType(String subject, int credentialsVersion) {
        return signed(subject, serviceKey(), 0).claim(JwtUtil.CLAIM_CREDENTIALS_VERSION, credentialsVersion).compact();
    }

    /** Of the right type, but without a credentials version. */
    public static String withoutCredentialsVersion(String subject, TokenType type) {
        return signed(subject, serviceKey(), 0).setHeaderParam(Header.TYPE, type.name()).compact();
    }

    /** Everything right, except that its lifetime ended a minute ago. */
    public static String expired(String subject, int credentialsVersion, TokenType type) {
        return complete(signed(subject, serviceKey(), LIFETIME.toMinutes() + 1), credentialsVersion, type).compact();
    }

    /** Everything right, but signed with a key that is not the service's. */
    public static String signedWithAnotherKey(String subject, int credentialsVersion, TokenType type) {
        Key otherKey = Keys.hmacShaKeyFor(OTHER_SECRET.getBytes(StandardCharsets.UTF_8));
        return complete(signed(subject, otherKey, 0), credentialsVersion, type).compact();
    }

    private static JwtBuilder complete(JwtBuilder builder, int credentialsVersion, TokenType type) {
        return builder.setHeaderParam(Header.TYPE, type.name()).claim(JwtUtil.CLAIM_CREDENTIALS_VERSION, credentialsVersion);
    }

    /** Subject, unique id and a lifetime of {@link #LIFETIME} that started {@code minutesAgo} minutes ago. */
    private static JwtBuilder signed(String subject, Key key, long minutesAgo) {
        long issuedAt = System.currentTimeMillis() - Duration.ofMinutes(minutesAgo).toMillis();
        return Jwts.builder()
                .setSubject(subject)
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(new Date(issuedAt))
                .setExpiration(new Date(issuedAt + LIFETIME.toMillis()))
                .signWith(key, SignatureAlgorithm.HS256);
    }

    private static Key serviceKey() {
        return ReflectionTestUtils.invokeMethod(new JwtUtil(), "getSigningKey");
    }
}
