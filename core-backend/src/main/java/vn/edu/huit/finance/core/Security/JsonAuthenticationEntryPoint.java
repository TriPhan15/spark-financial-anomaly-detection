package vn.edu.huit.finance.core.Security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import vn.edu.huit.finance.core.Dto.ApiResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Answers an API request that reaches a protected URL without an authenticated caller, with the ApiResponse envelope
 * GlobalExceptionHandler uses (code mirroring the status, message, no data) as JSON: never Spring's error page, a stack
 * trace, the token or the account. Status and message come from the {@link TokenRejection} JwtAuthFilter recorded, or
 * {@link TokenRejection#MISSING} when the request had no token.
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        TokenRejection rejection = request.getAttribute(TokenRejection.REQUEST_ATTRIBUTE) instanceof TokenRejection recorded
                ? recorded
                : TokenRejection.MISSING;
        HttpStatus status = rejection.getStatus();

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.<Void>builder()
                .code(status.value())
                .message(rejection.getMessage())
                .build());
    }
}
