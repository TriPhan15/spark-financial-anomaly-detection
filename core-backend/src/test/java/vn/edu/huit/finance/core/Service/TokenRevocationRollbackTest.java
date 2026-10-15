package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.ChangePasswordRequestDto;
import vn.edu.huit.finance.core.Entity.RevokedToken;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.RevokedTokenRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtUtil;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Revoking a token and the change that caused it are one unit of work. Nothing here runs inside a test transaction (that
 * would hide the very commits and rollbacks under test): the services run through their real @Transactional proxies on
 * H2 with a real JwtUtil, and every check reads the database again afterwards.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AuthService.class, TokenBlacklistService.class, JwtUtil.class, CustomUserDetailsService.class,
        TokenRevocationRollbackTest.EncoderConfig.class})
class TokenRevocationRollbackTest {

    private static final String EMAIL = "alice@example.com";
    private static final String OLD_PASSWORD = "OldPass123";
    private static final String NEW_PASSWORD = "NewPass456";
    // What a fresh account starts with, and so what the tokens of these tests are issued for.
    private static final int VERSION = 0;
    private static final String HASH_OF_A_GONE_TOKEN = "a".repeat(RevokedToken.TOKEN_HASH_LENGTH);
    private static final String HASH_OF_A_LIVE_TOKEN = "b".repeat(RevokedToken.TOKEN_HASH_LENGTH);

    @TestConfiguration
    static class EncoderConfig {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    @Autowired private AuthService authService;
    @Autowired private TokenBlacklistService tokenBlacklistService;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @SpyBean private RevokedTokenRepository revokedTokenRepository;
    @MockBean private CurrentUserProvider currentUserProvider;
    @MockBean private AuditLogService auditLogService;
    @MockBean private JavaMailSender mailSender;

    private Long aliceId;

    @BeforeEach
    void setUp() {
        User alice = new User();
        alice.setUsername("alice");
        alice.setEmail(EMAIL);
        alice.setPhone("0900000001");
        alice.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        aliceId = userRepository.saveAndFlush(alice).getId();
        // Every call answers with a fresh read, inside whatever transaction the service has opened by then.
        when(currentUserProvider.getCurrentUser()).thenAnswer(invocation -> userRepository.findById(aliceId).orElseThrow());
    }

    @AfterEach
    void cleanUp() {
        revokedTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private static ChangePasswordRequestDto request(String oldPassword, String newPassword) {
        ChangePasswordRequestDto dto = new ChangePasswordRequestDto();
        dto.setOldPassword(oldPassword);
        dto.setNewPassword(newPassword);
        return dto;
    }

    private String passwordInDatabase() {
        return userRepository.findById(aliceId).orElseThrow().getPassword();
    }

    private int versionInDatabase() {
        return userRepository.findById(aliceId).orElseThrow().getCredentialsVersion();
    }

    // ------------------------------------------------------------------------------------------------- change password

    @Test
    void changePassword_commitsTheNewPasswordTheRevocationAndTheNewVersionTogether() {
        String token = jwtUtil.generateToken(EMAIL, VERSION);
        String tokenOfAnotherDevice = jwtUtil.generateToken(EMAIL, VERSION);

        authService.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), token);

        assertTrue(passwordEncoder.matches(NEW_PASSWORD, passwordInDatabase()));
        assertTrue(tokenBlacklistService.isRevoked(token));
        // The other device's token is not on the blacklist: it ends through the credentials version (CrossSessionTokenRevocationTest).
        assertFalse(tokenBlacklistService.isRevoked(tokenOfAnotherDevice), "only the token of the request is blacklisted");
        assertEquals(1, revokedTokenRepository.count());
        assertEquals(VERSION + 1, versionInDatabase());
    }

    @Test
    void changePassword_whenTheRevocationCannotBeSaved_thePasswordStaysAsItWas() {
        doThrow(new IllegalStateException("database went away")).when(revokedTokenRepository).save(any(RevokedToken.class));
        String token = jwtUtil.generateToken(EMAIL, VERSION);

        assertThrows(IllegalStateException.class,
                () -> authService.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), token));

        assertTrue(passwordEncoder.matches(OLD_PASSWORD, passwordInDatabase()),
                "the new password must not be committed without the revocation");
        assertEquals(0, revokedTokenRepository.count());
        // The one UPDATE of the password and the version had already run when the revocation failed; both go back with it.
        assertEquals(VERSION, versionInDatabase(), "the sessions of the other devices must survive a change that failed");
    }

    @Test
    void changePassword_withAWrongOldPassword_revokesNothing() {
        String token = jwtUtil.generateToken(EMAIL, VERSION);

        assertThrows(RuntimeException.class, () -> authService.changePassword(request("wrong-old", NEW_PASSWORD), token));

        assertFalse(tokenBlacklistService.isRevoked(token), "a refused change must leave the session alone");
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, passwordInDatabase()));
        assertEquals(VERSION, versionInDatabase());
    }

    // ------------------------------------------------------------------------------------------------------- logout

    @Test
    void logout_revokesTheAccessTokenAndTheRefreshToken_andNothingElse() {
        String access = jwtUtil.generateToken(EMAIL, VERSION);
        String refresh = jwtUtil.generateRefreshToken(EMAIL, VERSION);
        String tokenOfAnotherDevice = jwtUtil.generateToken(EMAIL, VERSION);

        authService.logout(access, refresh);

        assertTrue(tokenBlacklistService.isRevoked(access));
        assertTrue(tokenBlacklistService.isRevoked(refresh));
        assertFalse(tokenBlacklistService.isRevoked(tokenOfAnotherDevice));
    }

    @Test
    void logout_withAGarbageRefreshToken_stillRevokesTheAccessToken() {
        String access = jwtUtil.generateToken(EMAIL, VERSION);

        authService.logout(access, "not.a.jwt");

        assertTrue(tokenBlacklistService.isRevoked(access));
        assertEquals(1, revokedTokenRepository.count());
    }

    @Test
    void logout_whenTheRevocationCannotBeSaved_nothingIsRevoked() {
        doThrow(new IllegalStateException("database went away")).when(revokedTokenRepository).save(any(RevokedToken.class));
        String access = jwtUtil.generateToken(EMAIL, VERSION);

        assertThrows(IllegalStateException.class, () -> authService.logout(access, jwtUtil.generateRefreshToken(EMAIL, VERSION)));

        assertEquals(0, revokedTokenRepository.count());
    }

    // ------------------------------------------------------------------------------------------------------- purging

    @Test
    void purgeExpired_removesTheRowsOfTokensThatAreGone_andKeepsTheOthers() {
        LocalDateTime now = LocalDateTime.now();
        revokedTokenRepository.saveAndFlush(new RevokedToken(HASH_OF_A_GONE_TOKEN, now.minusHours(1), now.minusHours(2)));
        revokedTokenRepository.saveAndFlush(new RevokedToken(HASH_OF_A_LIVE_TOKEN, now.plusHours(1), now.minusMinutes(5)));

        tokenBlacklistService.purgeExpired();

        assertFalse(revokedTokenRepository.existsByTokenHash(HASH_OF_A_GONE_TOKEN));
        assertTrue(revokedTokenRepository.existsByTokenHash(HASH_OF_A_LIVE_TOKEN));
    }
}
