package vn.edu.huit.finance.core.Security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.UserNotFoundException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CurrentUserProviderTest {

    private static final String USERNAME = "alice";

    @Mock private CustomUserDetailsService userDetailsService;

    private CurrentUserProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CurrentUserProvider();
        ReflectionTestUtils.setField(provider, "userDetailsService", userDetailsService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @Test
    void getCurrentUser_returnsTheUserBehindThePrincipal() {
        User alice = new User();
        authenticateAs(USERNAME);
        when(userDetailsService.loadApplicationUser(USERNAME)).thenReturn(alice);

        assertSame(alice, provider.getCurrentUser());
    }

    @Test
    void getCurrentUser_throwsUserNotFound_andKeepsTheCause_whenPrincipalMatchesNoUser() {
        authenticateAs(USERNAME);
        when(userDetailsService.loadApplicationUser(USERNAME)).thenThrow(new UsernameNotFoundException("gone"));

        UserNotFoundException thrown = assertThrows(UserNotFoundException.class, () -> provider.getCurrentUser());

        assertInstanceOf(UsernameNotFoundException.class, thrown.getCause());
    }

    @Test
    void getCurrentUser_throwsUserNotFound_withoutLookup_whenAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThrows(UserNotFoundException.class, () -> provider.getCurrentUser());

        verifyNoInteractions(userDetailsService);
    }

    @Test
    void getCurrentUser_throwsUserNotFound_withoutLookup_whenTheTokenIsNotAuthenticated() {
        // The two-argument constructor builds a token that is still waiting to be authenticated.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(USERNAME, "secret"));

        assertThrows(UserNotFoundException.class, () -> provider.getCurrentUser());

        verifyNoInteractions(userDetailsService);
    }

    @Test
    void getCurrentUser_throwsUserNotFound_withoutLookup_whenNoAuthentication() {
        assertThrows(UserNotFoundException.class, () -> provider.getCurrentUser());

        verifyNoInteractions(userDetailsService);
    }
}
