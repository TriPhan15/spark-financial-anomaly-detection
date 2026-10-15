package vn.edu.huit.finance.core.Security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.UserRepository;

import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
        User user = loadApplicationUser(usernameOrEmail);

        if (isLocked(user)) {
            throw new DisabledException("Account is locked");
        }

        String role = user.getRole().startsWith("ROLE_") ? user.getRole() : "ROLE_" + user.getRole();

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                user.isActive(),
                true,
                true,
                !Roles.LOCKED.equals(user.getRole()),
                Collections.singletonList(new SimpleGrantedAuthority(role))
        );
    }

    /**
     * True for an account that must not use the API any more: deactivated, or given the locked role. Static on purpose:
     * the sign-in, the JWT filter and the token refresh all apply this one rule, also where this service is mocked.
     */
    public static boolean isLocked(User user) {
        return !user.isActive() || Roles.LOCKED.equals(user.getRole());
    }

    public User loadApplicationUser(String usernameOrEmail) throws UsernameNotFoundException {
        return userRepository.findByUsername(usernameOrEmail)
                .or(() -> userRepository.findByEmail(usernameOrEmail))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + usernameOrEmail));
    }
}
