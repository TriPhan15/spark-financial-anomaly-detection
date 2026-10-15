package vn.edu.huit.finance.core.Controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.TokenClaims;
import vn.edu.huit.finance.core.Security.TokenType;
import vn.edu.huit.finance.core.Service.AuditLogService;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;
import vn.edu.huit.finance.core.Service.UserService;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/users/{id} is the profile screen of the app. It shares UserService.updateUser with the administrator's user
 * editor, and used to accept a "role" field from anybody: {"role":"ADMIN"} made the caller an administrator. The real
 * UserService runs here, behind the real filter chain, so the whole path from the request to the save is covered.
 */
@WebMvcTest(UserController.class)
@ContextConfiguration(classes = {
        UserController.class, UserService.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
class UserControllerRoleTest {

    private static final String TOKEN = "user.jwt.token";
    private static final Long ALICE_ID = 1L;

    @Autowired private MockMvc mockMvc;

    @MockBean private UserRepository userRepository;
    // UserService is real here and records every change it makes; what it records is UserServiceAuditTest's business.
    @MockBean private AuditLogService auditLogService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private CustomUserDetailsService userDetailsService;
    @MockBean private LoginAttemptService loginAttemptService;
    @MockBean private TokenBlacklistService tokenBlacklistService;

    private User alice;

    @BeforeEach
    void signInAsAlice() {
        alice = new User();
        alice.setId(ALICE_ID);
        alice.setUsername("alice");
        alice.setEmail("alice@example.com");
        alice.setPhone("0900000001");
        alice.setRole("ROLE_USER");
        alice.setActive(true);
        alice.setLastActionTime(LocalDateTime.now());
        when(jwtUtil.parse(TOKEN, TokenType.ACCESS)).thenReturn(new TokenClaims("alice", 0));
        when(userDetailsService.loadApplicationUser("alice")).thenReturn(alice);
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(new org.springframework.security.core.userdetails.User(
                "alice", "hash", AuthorityUtils.createAuthorityList("ROLE_USER")));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(userRepository.findById(ALICE_ID)).thenReturn(Optional.of(alice));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private MockHttpServletRequestBuilder updateProfile(Long id, String body) {
        return put("/api/users/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "admin", "ROLE_ADMIN", " role_admin "})
    void aUserCannotMakeThemselvesAnAdministratorInAnySpelling(String role) throws Exception {
        mockMvc.perform(updateProfile(ALICE_ID, "{\"role\":\"" + role + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        assertEquals("ROLE_USER", alice.getRole());
        verify(userRepository, never()).save(any());
    }

    @Test
    void aRefusedRoleChangeTakesTheRestOfTheSameRequestWithIt() throws Exception {
        mockMvc.perform(updateProfile(ALICE_ID, "{\"role\":\"ADMIN\",\"phone\":\"0911111111\"}"))
                .andExpect(status().isForbidden());

        assertEquals("0900000001", alice.getPhone());
        verify(userRepository, never()).save(any());
    }

    @Test
    void theProfileScreenStillUpdatesTheProfile() throws Exception {
        // What the app sends today: no role at all.
        mockMvc.perform(updateProfile(ALICE_ID, "{\"fullName\":\"Alice\",\"phone\":\"0911111111\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("0911111111"))
                .andExpect(jsonPath("$.data.role").value("ROLE_USER"));

        assertEquals("0911111111", alice.getPhone());
        verify(userRepository).save(alice);
    }

    @Test
    void sendingTheCurrentRoleBackIsHarmless() throws Exception {
        mockMvc.perform(updateProfile(ALICE_ID, "{\"role\":\"ROLE_USER\",\"phone\":\"0922222222\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ROLE_USER"));

        assertEquals("0922222222", alice.getPhone());
    }

    @Test
    void aUserCannotEditSomebodyElsesProfile() throws Exception {
        User bob = new User();
        bob.setId(2L);
        bob.setRole("ROLE_USER");
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));

        mockMvc.perform(updateProfile(2L, "{\"phone\":\"0933333333\"}"))
                .andExpect(status().is4xxClientError());

        verify(userRepository, never()).save(any());
    }
}
