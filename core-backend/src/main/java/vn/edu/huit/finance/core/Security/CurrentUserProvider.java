package vn.edu.huit.finance.core.Security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.UserNotFoundException;

/** Resolves the caller of the current request; the user always comes from the SecurityContext (the JWT), never from client input. */
@Slf4j
@Component
public class CurrentUserProvider {

    private static final String MSG_USER_NOT_FOUND = "Không tìm thấy người dùng đang đăng nhập";

    @Autowired
    private CustomUserDetailsService userDetailsService;

    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            log.warn("Request has no authenticated principal");
            throw new UserNotFoundException(MSG_USER_NOT_FOUND);
        }
        try {
            return userDetailsService.loadApplicationUser(authentication.getName());
        } catch (UsernameNotFoundException e) {
            log.warn("No user matches principal '{}'", authentication.getName());
            throw new UserNotFoundException(MSG_USER_NOT_FOUND, e);
        }
    }
}
