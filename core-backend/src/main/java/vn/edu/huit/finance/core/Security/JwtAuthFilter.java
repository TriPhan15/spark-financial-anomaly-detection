package vn.edu.huit.finance.core.Security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Turns the bearer token of an API request into the authenticated caller. It never answers a request itself: a token it
 * cannot use (expired, bad signature, not a JWT, a refresh token, no type or no credentials version, revoked, issued
 * before the last password change, account gone or locked) leaves the request anonymous, with the reason recorded as a
 * {@link TokenRejection}. A public endpoint serves it like any anonymous request; on a protected one Spring Security's
 * {@link JsonAuthenticationEntryPoint} answers with the JSON 401 (403 for a locked account).
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    // The last action time is written at most once per this many minutes per user.
    private static final long LAST_ACTION_UPDATE_INTERVAL_MINUTES = 5;
    // Which token was refused, and the method and path (never the query string) of the request that carried it.
    private static final String AUDIT_TOKEN_REJECTED = "Access token bị từ chối (%s): %s %s";

    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private CustomUserDetailsService userDetailsService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TokenBlacklistService tokenBlacklistService;
    // Only a signed token that may no longer be used is recorded (AUTH_TOKEN_REJECTED): one that is expired, garbage or of
    // the wrong type is routine (the app renews on 401) or can be sent by anybody, and would only flood the log.
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestPath = request.getServletPath();
        // Public auth endpoints skip JWT parsing; change-password and logout need it to identify the caller.
        if (AuthPaths.isPublic(requestPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = BearerToken.extract(request.getHeader(HttpHeaders.AUTHORIZATION)).orElse(null);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request, token);
        }
        filterChain.doFilter(request, response);
    }

    /** Authenticates the caller of a usable access token; any other token is rejected and the request stays anonymous. */
    private void authenticate(HttpServletRequest request, String token) {
        TokenClaims claims;
        try {
            // Signature, expiry, type ACCESS and a credentials version, all from one parse of the token.
            claims = jwtUtil.parse(token, TokenType.ACCESS);
        } catch (ExpiredJwtException e) {
            reject(request, TokenRejection.EXPIRED);
            return;
        } catch (JwtException | IllegalArgumentException e) {
            // Only the kind of failure is logged: the message could quote the token.
            log.debug("Access token refused by the parser: {}", e.getClass().getSimpleName());
            reject(request, TokenRejection.INVALID);
            return;
        }
        // Signed, not expired and an access token, but revoked by a logout or a password change.
        if (tokenBlacklistService.isRevoked(token)) {
            log.warn("A revoked access token was presented");
            reject(request, TokenRejection.REVOKED);
            auditRejection(request, TokenRejection.REVOKED, claims.subject(), null);
            return;
        }
        try {
            User user = userDetailsService.loadApplicationUser(claims.subject());
            if (!user.hasCredentialsVersion(claims.credentialsVersion())) {
                log.warn("Token issued before the last password change, userId={}", user.getId());
                reject(request, TokenRejection.OUTDATED);
                auditRejection(request, TokenRejection.OUTDATED, claims.subject(), user.getId());
                return;
            }
            if (CustomUserDetailsService.isLocked(user)) {
                log.warn("Token of a locked account, userId={}", user.getId());
                reject(request, TokenRejection.ACCOUNT_LOCKED);
                auditRejection(request, TokenRejection.ACCOUNT_LOCKED, claims.subject(), user.getId());
                return;
            }

            org.springframework.security.core.userdetails.UserDetails userDetails =
                    userDetailsService.loadUserByUsername(claims.subject());

            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authToken);
            recordActivity(user);
        } catch (AuthenticationException e) {
            // The account is gone. Not e.getMessage(), which names the account.
            log.debug("Token of an account that cannot be loaded: {}", e.getClass().getSimpleName());
            reject(request, TokenRejection.INVALID);
        }
    }

    /** No caller for this request; the reason is left for the entry point in case the URL turns out to be protected. */
    private void reject(HttpServletRequest request, TokenRejection rejection) {
        SecurityContextHolder.clearContext();
        request.setAttribute(TokenRejection.REQUEST_ATTRIBUTE, rejection);
        log.debug("Bearer token not accepted ({}), the request goes on anonymously", rejection);
    }

    /**
     * The request is not the account's: whoever sent it only claims the token's subject (signed by us, so it names the
     * account the token was issued to). The account is the target when it was loaded.
     */
    private void auditRejection(HttpServletRequest request, TokenRejection rejection, String subject, Long accountId) {
        eventPublisher.publishEvent(SecurityAuditEvent.of(AuditAction.AUTH_TOKEN_REJECTED,
                AuditActor.unauthenticated(subject), accountId,
                String.format(AUDIT_TOKEN_REJECTED, rejection, request.getMethod(), request.getRequestURI())));
    }

    /**
     * One UPDATE of last_action_time only, never a save of the user: the copy read above may already be stale, and saving
     * it would write its old balance and profile over what another request has committed meanwhile.
     */
    private void recordActivity(User user) {
        // 🔥 FIX CHÍ MẠNG: Chỉ lưu Database nếu lần tương tác cuối đã quá 5 phút
        LocalDateTime now = LocalDateTime.now();
        if (user.getLastActionTime() != null
                && Duration.between(user.getLastActionTime(), now).toMinutes() <= LAST_ACTION_UPDATE_INTERVAL_MINUTES) {
            return;
        }
        if (userRepository.updateLastActionTime(user.getId(), now) == 0) {
            // Deleted since it was loaded: nothing to record, and the endpoint decides what that means for the request.
            log.debug("Last action time not recorded, the account is gone: userId={}", user.getId());
        }
    }
}