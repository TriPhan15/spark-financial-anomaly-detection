package vn.edu.huit.finance.core.Service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.UserRepository;

import java.util.Map;

@Service
public class SocialAuthService {

    private final UserRepository userRepository;
    private final AuthService authService;
    private final RestClient http = RestClient.create();

    @Value("${app.google.web-client-id:}")
    private String googleWebClientId;

    public SocialAuthService(UserRepository userRepository, AuthService authService) {
        this.userRepository = userRepository;
        this.authService = authService;
    }

    public User authenticate(String provider, String token) {
        final String email;
        final String name;
        if ("google".equalsIgnoreCase(provider)) {
            Map<?, ?> info = verifyGoogle(token);
            email = str(info.get("email"));
            name = str(info.get("name"));
        } else if ("facebook".equalsIgnoreCase(provider)) {
            Map<?, ?> info = verifyFacebook(token);
            email = str(info.get("email"));
            name = str(info.get("name"));
        } else {
            throw new RuntimeException("Provider không hỗ trợ: " + provider);
        }

        if (email == null || email.isBlank()) {
            throw new RuntimeException("Provider không trả về email (yêu cầu quyền email).");
        }
        return findOrCreate(email, name, provider);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> verifyGoogle(String idToken) {
        try {
            Map<?, ?> info = http.get()
                    .uri("https://oauth2.googleapis.com/tokeninfo?id_token={t}", idToken)
                    .retrieve()
                    .body(Map.class);
            if (info == null) throw new RuntimeException("Token Google không hợp lệ");
            String aud = str(info.get("aud"));
            if (googleWebClientId != null && !googleWebClientId.isBlank()
                    && !googleWebClientId.equals(aud)) {
                throw new RuntimeException("Google token aud không khớp web-client-id");
            }
            return info;
        } catch (RuntimeException e) {
            throw new RuntimeException("Xác thực Google thất bại: " + e.getMessage());
        }
    }

    private Map<?, ?> verifyFacebook(String accessToken) {
        try {
            Map<?, ?> info = http.get()
                    .uri("https://graph.facebook.com/me?fields=id,name,email&access_token={t}", accessToken)
                    .retrieve()
                    .body(Map.class);
            if (info == null || info.get("id") == null) {
                throw new RuntimeException("Token Facebook không hợp lệ");
            }
            return info;
        } catch (RuntimeException e) {
            throw new RuntimeException("Xác thực Facebook thất bại: " + e.getMessage());
        }
    }

    /** The account of a verified email; the first sign-in creates it (with its audit row, in its own transaction). */
    private User findOrCreate(String email, String name, String provider) {
        return userRepository.findByEmail(email).orElseGet(() -> authService.registerSocialAccount(email, provider));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
