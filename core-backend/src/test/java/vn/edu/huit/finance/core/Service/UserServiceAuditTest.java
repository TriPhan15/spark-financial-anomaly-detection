package vn.edu.huit.finance.core.Service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.edu.huit.finance.core.Dto.UserCreateDto;
import vn.edu.huit.finance.core.Dto.UserUpdateDto;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.Roles;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * What the account changes record. The actor is always the caller of the request (an administrator, or the user editing
 * their own profile), looked up when the row is written; the target is the account changed. A row names the fields that
 * changed, never their values, except the role and the active flag, which are what such a change is about.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceAuditTest {

    private static final Long ID = 5L;

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private UserService service;

    private static User alice() {
        User user = new User();
        user.setId(ID);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPhone("0900000001");
        user.setRole(Roles.USER);
        user.setActive(true);
        return user;
    }

    private User loaded(User user) {
        when(userRepository.findById(ID)).thenReturn(Optional.of(user));
        return user;
    }

    private void savingAnswersWithTheUser() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private List<AuditEntry> recorded() {
        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService, atLeastOnce()).record(entries.capture());
        return entries.getAllValues();
    }

    private static void assertByTheCallerAbout(AuditEntry entry, AuditAction action, Long target) {
        assertEquals(action, entry.action());
        assertEquals(AuditActor.currentCaller(), entry.actor(), "the administrator, never the account changed");
        assertEquals(target, entry.targetId());
    }

    @Test
    void anAccountCreatedByAnAdministratorIsRecordedWithItsRole_neverItsPassword() {
        when(passwordEncoder.encode("secret1")).thenReturn("$2a$04$hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(77L);
            return user;
        });
        UserCreateDto dto = new UserCreateDto();
        dto.setUsername("new");
        dto.setEmail("new@example.com");
        dto.setPhone("0900000002");
        dto.setPassword("secret1");
        dto.setRole("admin");

        service.createUser(dto);

        AuditEntry entry = recorded().get(0);
        assertByTheCallerAbout(entry, AuditAction.USER_CREATED, 77L);
        assertEquals("Quản trị viên tạo tài khoản new@example.com, vai trò ROLE_ADMIN", entry.description());
        assertFalse(entry.description().contains("secret1"));
        assertFalse(entry.description().contains("$2a$04$hash"));
    }

    @Test
    void anAdministratorsEditNamesTheFieldsThatChanged_andARoleChangeIsRecordedOnItsOwn() {
        loaded(alice());
        savingAnswersWithTheUser();
        UserUpdateDto dto = new UserUpdateDto();
        dto.setPhone("0911111111");
        dto.setEmail("alice@example.com");
        dto.setRole("admin");

        service.updateUser(ID, dto);

        List<AuditEntry> entries = recorded();
        assertEquals(2, entries.size());
        assertByTheCallerAbout(entries.get(0), AuditAction.USER_UPDATED, ID);
        assertEquals("Quản trị viên cập nhật tài khoản: số điện thoại", entries.get(0).description(),
                "the email sent is the one it had: not a change");
        assertFalse(entries.get(0).description().contains("0911111111"), "the new value is not repeated");
        assertByTheCallerAbout(entries.get(1), AuditAction.USER_ROLE_CHANGED, ID);
        assertEquals("Vai trò ROLE_USER → ROLE_ADMIN", entries.get(1).description());
    }

    @Test
    void anEditWithoutANewRoleRecordsNoRoleChange() {
        loaded(alice());
        savingAnswersWithTheUser();
        UserUpdateDto dto = new UserUpdateDto();
        dto.setUsername("alice2");
        dto.setRole("user");

        service.updateUser(ID, dto);

        assertEquals(List.of(AuditAction.USER_UPDATED), recorded().stream().map(AuditEntry::action).toList());
    }

    @Test
    void aUserEditingTheirOwnProfileIsRecordedAsSuch() {
        loaded(alice());
        savingAnswersWithTheUser();
        UserUpdateDto dto = new UserUpdateDto();
        dto.setUsername("alice2");

        service.updateOwnProfile(ID, dto);

        AuditEntry entry = recorded().get(0);
        assertByTheCallerAbout(entry, AuditAction.USER_UPDATED, ID);
        assertEquals("Người dùng tự cập nhật hồ sơ: tên đăng nhập", entry.description());
    }

    @Test
    void aRefusedAttemptToPromoteOneselfRecordsNoChange() {
        loaded(alice());
        UserUpdateDto dto = new UserUpdateDto();
        dto.setRole("ADMIN");

        assertThrows(AccessDeniedException.class, () -> service.updateOwnProfile(ID, dto));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void lockingAndUnlockingAnAccountAreRecordedWithTheNewState() {
        User alice = loaded(alice());

        service.toggleUserStatus(ID);
        service.toggleUserStatus(ID);

        List<AuditEntry> entries = recorded();
        assertByTheCallerAbout(entries.get(0), AuditAction.USER_STATUS_CHANGED, ID);
        assertEquals("Khóa tài khoản (active true → false)", entries.get(0).description());
        assertEquals("Mở khóa tài khoản (active false → true)", entries.get(1).description());
        assertTrue(alice.isActive());
    }

    @Test
    void anAdministratorAccountCannotBeLocked_andNothingIsRecorded() {
        User admin = alice();
        admin.setRole(Roles.ADMIN);
        loaded(admin);

        assertThrows(RuntimeException.class, () -> service.toggleUserStatus(ID));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void aDeletedAccountIsRecordedWithTheEmailItHad() {
        loaded(alice());

        service.deleteUser(ID);

        AuditEntry entry = recorded().get(0);
        assertByTheCallerAbout(entry, AuditAction.USER_DELETED, ID);
        assertEquals("Xóa vĩnh viễn tài khoản alice@example.com", entry.description());
    }

    @Test
    void anAccountThatCouldNotBeDeletedIsNotRecordedAsDeleted() {
        loaded(alice());
        doThrow(new DataIntegrityViolationException("fk_transactions_user")).when(userRepository).flush();

        assertThrows(RuntimeException.class, () -> service.deleteUser(ID));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void anAccountThatDoesNotExistIsNotFound_andNothingIsRecorded() {
        when(userRepository.findById(ID)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.updateUser(ID, new UserUpdateDto()));
        assertThrows(EntityNotFoundException.class, () -> service.deleteUser(ID));

        verifyNoInteractions(auditLogService);
    }
}
