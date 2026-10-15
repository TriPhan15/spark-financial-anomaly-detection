package vn.edu.huit.finance.core.Security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MissingClaimException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The token format that session revocation relies on. No Spring context: JwtUtil has no dependencies. */
class JwtUtilTest {

    private static final String SUBJECT = "alice@example.com";
    private static final int VERSION = 7;
    private static final int TOKENS = 50;

    private final JwtUtil jwtUtil = new JwtUtil();

    private String issue(TokenType type, int version) {
        return type == TokenType.ACCESS
                ? jwtUtil.generateToken(SUBJECT, version)
                : jwtUtil.generateRefreshToken(SUBJECT, version);
    }

    private static TokenType theOther(TokenType type) {
        return type == TokenType.ACCESS ? TokenType.REFRESH : TokenType.ACCESS;
    }

    /** The decoded JOSE header of a token, the part before the first dot. */
    private static String headerOf(String token) {
        return new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))), StandardCharsets.UTF_8);
    }

    @Test
    void tokensOfTheSameUserIssuedInTheSameInstantAreStillDifferent_soRevokingOneNeverRevokesTheNextLogin() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < TOKENS; i++) {
            tokens.add(jwtUtil.generateToken(SUBJECT, VERSION));
        }

        // Without a unique id, everything issued in one second to one user would be the very same string.
        assertEquals(TOKENS, tokens.size());
    }

    @Test
    void refreshTokensAreUniqueToo() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < TOKENS; i++) {
            tokens.add(jwtUtil.generateRefreshToken(SUBJECT, VERSION));
        }

        assertEquals(TOKENS, tokens.size());
    }

    @Test
    void extractExpiration_ofAnAccessToken_isAboutFifteenMinutesAhead() {
        String token = jwtUtil.generateToken(SUBJECT, VERSION);

        long millisAhead = jwtUtil.extractExpiration(token).getTime() - System.currentTimeMillis();

        // The exp claim has whole-second precision, so it may be up to a second short of the full lifetime.
        assertTrue(millisAhead > Duration.ofMinutes(14).toMillis() && millisAhead <= Duration.ofMinutes(15).toMillis(),
                "expires in " + millisAhead + " ms");
    }

    @Test
    void extractExpiration_ofARefreshToken_isAboutThirtyDaysAhead() {
        String token = jwtUtil.generateRefreshToken(SUBJECT, VERSION);

        Date expiration = jwtUtil.extractExpiration(token);

        long millisAhead = expiration.getTime() - System.currentTimeMillis();
        assertTrue(millisAhead > Duration.ofDays(29).toMillis() && millisAhead <= Duration.ofDays(30).toMillis(),
                "expires in " + millisAhead + " ms");
    }

    @Test
    void extractExpiration_throwsForATokenThatIsNotValid() {
        assertThrows(JwtException.class, () -> jwtUtil.extractExpiration("not.a.jwt"));
    }

    @Test
    void validateToken_acceptsATokenItIssued_andRejectsATamperedOne() {
        String token = jwtUtil.generateToken(SUBJECT, VERSION);

        assertTrue(jwtUtil.validateToken(token));
        assertFalse(jwtUtil.validateToken(token + "x"));
        assertFalse(jwtUtil.validateToken("not.a.jwt"));
    }

    @Test
    void aTokenWithoutACredentialsVersionIsNeverIssued() {
        assertThrows(NullPointerException.class, () -> jwtUtil.generateToken(SUBJECT, null));
        assertThrows(NullPointerException.class, () -> jwtUtil.generateRefreshToken(SUBJECT, null));
    }

    // ------------------------------------------------------------------------------------------- token type + version

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void everyTokenNamesItsTypeInTheJoseHeader_notInThePayload(TokenType type) {
        String header = headerOf(issue(type, VERSION));

        assertTrue(header.contains("\"typ\":\"" + type.name() + "\""), header);
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void parse_returnsTheSubjectAndTheCredentialsVersion_ofATokenOfTheExpectedType(TokenType type) {
        TokenClaims claims = jwtUtil.parse(issue(type, VERSION), type);

        assertEquals(SUBJECT, claims.subject());
        assertEquals(VERSION, claims.credentialsVersion());
        assertEquals(0, jwtUtil.parse(issue(type, 0), type).credentialsVersion());
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void parse_refusesATokenOfTheOtherType(TokenType type) {
        // A refresh token presented as a bearer token, or an access token traded in at /refresh.
        String token = issue(theOther(type), VERSION);

        assertThrows(UnsupportedJwtException.class, () -> jwtUtil.parse(token, type));
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void parse_refusesATokenOfThePreviousRelease_thatIsStillValidlySigned(TokenType type) {
        String legacy = CraftedTokens.legacy(SUBJECT);

        // Signature and expiry alone would let it in: only the missing type and version tell it apart.
        assertTrue(jwtUtil.validateToken(legacy));
        assertThrows(UnsupportedJwtException.class, () -> jwtUtil.parse(legacy, type));
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void parse_refusesATokenWithoutAType(TokenType type) {
        assertThrows(UnsupportedJwtException.class, () -> jwtUtil.parse(CraftedTokens.withoutType(SUBJECT, VERSION), type));
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void parse_refusesATokenWithoutACredentialsVersion(TokenType type) {
        String token = CraftedTokens.withoutCredentialsVersion(SUBJECT, type);

        assertThrows(MissingClaimException.class, () -> jwtUtil.parse(token, type));
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void parse_refusesAnExpiredToken_withTheExceptionThatSetsItApart(TokenType type) {
        String token = CraftedTokens.expired(SUBJECT, VERSION, type);

        assertThrows(ExpiredJwtException.class, () -> jwtUtil.parse(token, type));
        assertFalse(jwtUtil.validateToken(token));
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void parse_refusesATokenSignedWithAnotherKey(TokenType type) {
        String token = CraftedTokens.signedWithAnotherKey(SUBJECT, VERSION, type);

        assertThrows(SignatureException.class, () -> jwtUtil.parse(token, type));
    }

    @Test
    void parse_refusesATamperedToken_garbage_andNothing() {
        String tampered = jwtUtil.generateToken(SUBJECT, VERSION) + "x";

        assertThrows(JwtException.class, () -> jwtUtil.parse(tampered, TokenType.ACCESS));
        assertThrows(JwtException.class, () -> jwtUtil.parse("not.a.jwt", TokenType.ACCESS));
        assertThrows(IllegalArgumentException.class, () -> jwtUtil.parse("", TokenType.ACCESS));
        assertThrows(IllegalArgumentException.class, () -> jwtUtil.parse(null, TokenType.ACCESS));
    }
}
