package vn.edu.huit.finance.core.Service;

import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final ConcurrentHashMap<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public void assertLoginAllowed(String email, String ipAddress) {
        if (isLocked(emailKey(email)) || isLocked(ipKey(ipAddress))) {
            throw new LockedException("Too many failed login attempts. Try again in 15 minutes.");
        }
    }

    public void recordSuccess(String email, String ipAddress) {
        remove(emailKey(email));
        remove(ipKey(ipAddress));
    }

    public void recordFailure(String email, String ipAddress) {
        recordFailure(emailKey(email));
        recordFailure(ipKey(ipAddress));
    }

    private void recordFailure(String key) {
        if (key == null) {
            return;
        }

        attempts.compute(key, (ignored, current) -> {
            LocalDateTime now = LocalDateTime.now();
            if (current != null && current.isLocked(now)) {
                return current;
            }

            int failedAttempts = current == null || current.isExpired(now)
                    ? 1
                    : current.failedAttempts() + 1;
            LocalDateTime lockedUntil = failedAttempts >= MAX_FAILED_ATTEMPTS
                    ? now.plus(LOCK_DURATION)
                    : null;
            return new AttemptState(failedAttempts, lockedUntil);
        });
    }

    private boolean isLocked(String key) {
        if (key == null) {
            return false;
        }

        AttemptState state = attempts.get(key);
        if (state == null) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        if (state.isLocked(now)) {
            return true;
        }
        if (state.isExpired(now)) {
            attempts.remove(key, state);
        }
        return false;
    }
    public void resetAttempts(String email, String ipAddress) {
        if (email != null && !email.isBlank()) {
            remove(emailKey(email));
        }
        if (ipAddress != null && !ipAddress.isBlank()) {
            remove(ipKey(ipAddress));
        }
    }
    private void remove(String key) {
        if (key != null) {
            attempts.remove(key);
        }
    }

    private String emailKey(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return "email:" + email.trim().toLowerCase(Locale.ROOT);
    }

    private String ipKey(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return null;
        }
        return "ip:" + ipAddress.trim();
    }

    private record AttemptState(int failedAttempts, LocalDateTime lockedUntil) {
        boolean isLocked(LocalDateTime now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }

        boolean isExpired(LocalDateTime now) {
            return lockedUntil != null && !lockedUntil.isAfter(now);
        }
    }
}
