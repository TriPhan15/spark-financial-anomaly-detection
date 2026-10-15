package vn.edu.huit.finance.core.Security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import vn.edu.huit.finance.core.Dto.ApiResponse;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Answers a signed-in caller that a URL rule of SecurityConfig refuses (a user on /api/admin/**, on /admin/**), after
 * recording it as AUTH_ACCESS_DENIED. Spring Security only calls it for an authenticated caller: one without a session or
 * token goes to the authentication entry point (401, or the login page) instead and is not recorded here. The API chain
 * answers with the ApiResponse JSON every other 403 of the API uses; the admin pages keep the container's 403 page.
 */
public class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    /** The message of every 403 of the API, also the one GlobalExceptionHandler answers for @PreAuthorize. */
    public static final String MSG_ACCESS_DENIED = "Bảo mật: Bạn không có quyền (Role) để thực hiện hành động này!";
    private static final String REASON_URL_RULE = "Quy tắc phân quyền theo URL";

    private final ApplicationEventPublisher eventPublisher;
    private final AccessDeniedHandler answer;

    private AuditingAccessDeniedHandler(ApplicationEventPublisher eventPublisher, AccessDeniedHandler answer) {
        this.eventPublisher = eventPublisher;
        this.answer = answer;
    }

    /** For the API: a JSON ApiResponse with code 403. */
    public static AuditingAccessDeniedHandler json(ApplicationEventPublisher eventPublisher, ObjectMapper objectMapper) {
        return new AuditingAccessDeniedHandler(eventPublisher, (request, response, exception) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), ApiResponse.<Void>builder()
                    .code(HttpStatus.FORBIDDEN.value())
                    .message(MSG_ACCESS_DENIED)
                    .build());
        });
    }

    /** For the admin pages: the default answer of Spring Security, the container's 403 error page. */
    public static AuditingAccessDeniedHandler page(ApplicationEventPublisher eventPublisher) {
        return new AuditingAccessDeniedHandler(eventPublisher, new AccessDeniedHandlerImpl());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException, ServletException {
        eventPublisher.publishEvent(
                SecurityAuditEvent.accessDenied(REASON_URL_RULE, request.getMethod(), request.getRequestURI()));
        answer.handle(request, response, exception);
    }
}
