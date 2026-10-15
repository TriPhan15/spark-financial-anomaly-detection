package vn.edu.huit.finance.core.Service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.coyote.BadRequestException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.UserCreateDto;
import vn.edu.huit.finance.core.Dto.UserUpdateDto;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.Roles;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChange;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChangedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Accounts as administrators manage them, and the profile a user edits. Every change is recorded in action_logs inside
 * its transaction: the actor is the caller of the request (the administrator, or the user editing their own profile),
 * looked up in the SecurityContext, and the target is the account changed. Rows name the fields that changed, never
 * their values, except the role and the active flag, which are what the change is about.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String MSG_ROLE_CHANGE_FORBIDDEN = "Không được phép tự thay đổi vai trò";
    private static final String AUDIT_CREATED = "Quản trị viên tạo tài khoản %s, vai trò %s";
    private static final String AUDIT_UPDATED_BY_ADMIN = "Quản trị viên cập nhật tài khoản: %s";
    private static final String AUDIT_UPDATED_BY_OWNER = "Người dùng tự cập nhật hồ sơ: %s";
    private static final String AUDIT_FIELD_USERNAME = "tên đăng nhập";
    private static final String AUDIT_FIELD_PHONE = "số điện thoại";
    private static final String AUDIT_FIELD_EMAIL = "email";
    private static final String AUDIT_NO_FIELD_CHANGED = "không có trường nào thay đổi";
    private static final String AUDIT_ROLE_CHANGED = "Vai trò %s → %s";
    private static final String AUDIT_DELETED = "Xóa vĩnh viễn tài khoản %s";
    private static final String AUDIT_LOCKED = "Khóa tài khoản (active true → false)";
    private static final String AUDIT_UNLOCKED = "Mở khóa tài khoản (active false → true)";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLogService;

    @Transactional
    public User createUser(UserCreateDto dto) {
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setRole(Roles.normalize(dto.getRole()));
        user.setActive(true);

        User created = userRepository.save(user);
        eventPublisher.publishEvent(StatisticsChangedEvent.of(StatisticsChange.ACCOUNT_CREATED));
        record(AuditAction.USER_CREATED, created.getId(), String.format(AUDIT_CREATED, created.getEmail(), created.getRole()));
        return created;
    }

    /** For administrators: any field of any user, the role included. */
    @Transactional
    public User updateUser(Long id, UserUpdateDto dto) {
        return update(id, dto, true);
    }

    /**
     * For a user editing their own profile: the same fields, but the role cannot change. Asking for a different role is
     * refused (403) instead of being ignored, since it is an attempt to promote oneself.
     */
    @Transactional
    public User updateOwnProfile(Long id, UserUpdateDto dto) {
        return update(id, dto, false);
    }

    private User update(Long id, UserUpdateDto dto, boolean mayChangeRole) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));

        String roleBefore = user.getRole();
        if (dto.getRole() != null && !dto.getRole().isBlank()) {
            String requestedRole = Roles.normalize(dto.getRole());
            if (mayChangeRole) {
                user.setRole(requestedRole);
            } else if (!requestedRole.equals(Roles.normalize(user.getRole()))) {
                log.warn("Role change refused, userId={}, requestedRole={}", id, requestedRole);
                throw new AccessDeniedException(MSG_ROLE_CHANGE_FORBIDDEN);
            }
        }
        List<String> changedFields = new ArrayList<>();
        if (dto.getUsername() != null && !dto.getUsername().isBlank()) {
            addIfChanged(changedFields, AUDIT_FIELD_USERNAME, user.getUsername(), dto.getUsername());
            user.setUsername(dto.getUsername());
        }
        if (dto.getPhone() != null && !dto.getPhone().isBlank()) {
            addIfChanged(changedFields, AUDIT_FIELD_PHONE, user.getPhone(), dto.getPhone());
            user.setPhone(dto.getPhone());
        }
        if (dto.getEmail() != null && !dto.getEmail().isBlank()) {
            addIfChanged(changedFields, AUDIT_FIELD_EMAIL, user.getEmail(), dto.getEmail());
            user.setEmail(dto.getEmail());
        }

        User saved = userRepository.save(user);
        eventPublisher.publishEvent(StatisticsChangedEvent.of(StatisticsChange.ACCOUNT_PROFILE, id));
        String fields = changedFields.isEmpty() ? AUDIT_NO_FIELD_CHANGED : String.join(", ", changedFields);
        record(AuditAction.USER_UPDATED, id, String.format(mayChangeRole ? AUDIT_UPDATED_BY_ADMIN : AUDIT_UPDATED_BY_OWNER, fields));
        if (!Objects.equals(roleBefore, saved.getRole())) {
            record(AuditAction.USER_ROLE_CHANGED, id, String.format(AUDIT_ROLE_CHANGED, roleBefore, saved.getRole()));
        }
        return saved;
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
        try {
            userRepository.delete(user);
            userRepository.flush();
        } catch (Exception e) {
            user.setActive(false);
            userRepository.save(user);
            throw new RuntimeException("Tài khoản đang có giao dịch. Hệ thống đã tự động vô hiệu hóa (Khóa) thay vì xóa vĩnh viễn để bảo toàn dữ liệu.");
        }
        eventPublisher.publishEvent(StatisticsChangedEvent.of(StatisticsChange.ACCOUNT_DELETED, id));
        record(AuditAction.USER_DELETED, id, String.format(AUDIT_DELETED, user.getEmail()));
    }

    private String buildAdminPlaceholderPhone(String username) {
        String suffix = Integer.toUnsignedString(username.hashCode());
        return "ADMIN_" + suffix;
    }
    @org.springframework.transaction.annotation.Transactional
    public void toggleUserStatus(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy người dùng với ID: " + userId));

        if (Roles.isAdmin(user.getRole())) {
            throw new RuntimeException("Không được phép tác động hoặc thay đổi trạng thái của ADMIN hệ thống!");
        }

        user.setActive(!user.isActive());
        userRepository.save(user);
        record(AuditAction.USER_STATUS_CHANGED, userId, user.isActive() ? AUDIT_UNLOCKED : AUDIT_LOCKED);
    }

    /** A row about account {@code userId}, by the caller of the request (looked up when the row is written). */
    private void record(AuditAction action, Long userId, String description) {
        auditLogService.record(AuditEntry.of(action, AuditActor.currentCaller(), userId, description));
    }

    private static void addIfChanged(List<String> changedFields, String field, String before, String after) {
        if (!Objects.equals(before, after)) {
            changedFields.add(field);
        }
    }
}
