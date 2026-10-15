package vn.edu.huit.finance.core.Config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.AuditingAccessDeniedHandler;
import vn.edu.huit.finance.core.Security.AuthPaths;
import vn.edu.huit.finance.core.Security.ClientIp;
import vn.edu.huit.finance.core.Security.JsonAuthenticationEntryPoint;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;
import vn.edu.huit.finance.core.Service.LoginAttemptService;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String AUDIT_ADMIN_LOGIN = "Đăng nhập trang quản trị";
    private static final String AUDIT_ADMIN_LOGIN_FAILED = "Trang quản trị: sai thông tin đăng nhập hoặc tài khoản không dùng được";
    private static final String AUDIT_ADMIN_LOCK_TRIGGERED = "Trang quản trị: khóa đăng nhập 15 phút sau quá nhiều lần nhập sai";
    private static final String AUDIT_ADMIN_LOCKED_ATTEMPT = "Trang quản trị: tài khoản hoặc IP đang bị khóa vẫn gửi lệnh đăng nhập";
    private static final String AUDIT_ADMIN_LOGOUT = "Đăng xuất trang quản trị";

    @Autowired
    private JwtAuthFilter jwtAuthFilter;
    @Autowired
    private LoginAttemptService loginAttemptService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ObjectMapper objectMapper;
    // Sign-ins, lockouts, sign-outs and refused URLs are recorded as SecurityAuditEvents, each in a row of its own.
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/admin/**", "/login", "/error", "/css/**", "/js/**", "/images/**", "/uploads/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**", "/uploads/**").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .successHandler((request, response, authentication) -> {
                            String username = request.getParameter("username");
                            String ipAddress = ClientIp.of(request);
                            loginAttemptService.recordSuccess(username, ipAddress);
                            Optional<User> account = updateLoginAudit(username);
                            eventPublisher.publishEvent(SecurityAuditEvent.of(AuditAction.AUTH_LOGIN_SUCCESS,
                                    account.map(AuditActor::of).orElseGet(() -> AuditActor.unauthenticated(username)),
                                    account.map(User::getId).orElse(null), AUDIT_ADMIN_LOGIN));
                            response.sendRedirect("/admin/dashboard");
                        })
                        .failureHandler((request, response, exception) -> {
                            String username = request.getParameter("username");
                            String ipAddress = ClientIp.of(request);
                            loginAttemptService.recordFailure(username, ipAddress);
                            try {
                                loginAttemptService.assertLoginAllowed(username, ipAddress);
                            } catch (org.springframework.security.authentication.LockedException lockedException) {
                                publishLoginRefusal(AuditAction.AUTH_LOGIN_LOCKED, username, AUDIT_ADMIN_LOCK_TRIGGERED);
                                response.sendRedirect("/admin/login?locked=true");
                                return;
                            }
                            publishLoginRefusal(AuditAction.AUTH_LOGIN_FAILED, username, AUDIT_ADMIN_LOGIN_FAILED);
                            response.sendRedirect("/admin/login?error=true");
                        })
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login")
                        // Runs before the SecurityContext is cleared; a logout without a session records nothing.
                        .addLogoutHandler((request, response, authentication) -> {
                            if (authentication != null) {
                                findAccount(authentication.getName()).ifPresent(account -> eventPublisher.publishEvent(
                                        SecurityAuditEvent.of(AuditAction.AUTH_LOGOUT, AuditActor.of(account),
                                                account.getId(), AUDIT_ADMIN_LOGOUT)));
                            }
                        })
                        .permitAll()
                )
                // A signed-in user who is no administrator: AUTH_ACCESS_DENIED, then the usual 403 page.
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler(AuditingAccessDeniedHandler.page(eventPublisher)))
                .addFilterBefore(adminLoginLockoutFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .cors(cors -> cors.configurationSource(request -> {
                    CorsConfiguration config = new CorsConfiguration();
                    config.setAllowedOrigins(List.of("*"));
                    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                    config.setAllowedHeaders(List.of("*"));
                    return config;
                }))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(AuthPaths.CHANGE_PASSWORD_URL, AuthPaths.LOGOUT_URL).authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/payment/webhook").permitAll()
                        .requestMatchers("/api/categories").permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                        .anyRequest().authenticated()
                )
                // A protected URL without an authenticated caller (no token, or one JwtAuthFilter could not use): JSON 401,
                // or 403 for a locked account. Public URLs never get here, they serve such a request anonymously. A signed-in
                // caller a URL rule refuses (a user on /api/admin/**): AUTH_ACCESS_DENIED and the JSON 403 of the API.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(AuditingAccessDeniedHandler.json(eventPublisher, objectMapper)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
    @Bean
    public org.springframework.security.authentication.AuthenticationManager authenticationManager(
            org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    private OncePerRequestFilter adminLoginLockoutFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                    throws ServletException, IOException {
                if ("POST".equalsIgnoreCase(request.getMethod()) && "/admin/login".equals(request.getServletPath())) {
                    String username = request.getParameter("username");
                    try {
                        loginAttemptService.assertLoginAllowed(username, ClientIp.of(request));
                    } catch (org.springframework.security.authentication.LockedException e) {
                        publishLoginRefusal(AuditAction.AUTH_LOGIN_LOCKED, username, AUDIT_ADMIN_LOCKED_ATTEMPT);
                        response.sendRedirect("/admin/login?locked=true");
                        return;
                    }
                }
                filterChain.doFilter(request, response);
            }
        };
    }

    /** A refused sign-in: the name typed in is only the claimed identity, no account is looked up for it. */
    private void publishLoginRefusal(AuditAction action, String username, String description) {
        eventPublisher.publishEvent(SecurityAuditEvent.of(action, AuditActor.unauthenticated(username), null, description));
    }

    private Optional<User> findAccount(String usernameOrEmail) {
        return userRepository.findByUsername(usernameOrEmail).or(() -> userRepository.findByEmail(usernameOrEmail));
    }

    private Optional<User> updateLoginAudit(String usernameOrEmail) {
        Optional<User> account = findAccount(usernameOrEmail);
        account.ifPresent(user -> {
            LocalDateTime now = LocalDateTime.now();
            user.setLastLoginTime(now);
            user.setLastActionTime(now);
            userRepository.save(user);
        });
        return account;
    }
}
