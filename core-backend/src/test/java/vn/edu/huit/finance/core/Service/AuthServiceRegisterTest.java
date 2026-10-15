package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.RegisterRequestDto;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChange;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChangedEvent;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * An account created by its owner (registration) or by a first social sign-in: built by the server from what may be
 * chosen, stored with its USER_CREATED row in one transaction, announced to the statistics cache.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceRegisterTest {

    private static final String EMAIL = "newbie@example.com";
    private static final String PASSWORD = "NewPass456";
    private static final long NEW_ID = 31L;

    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService();
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
    }

    private static RegisterRequestDto request() {
        RegisterRequestDto request = new RegisterRequestDto();
        request.setUsername("newbie");
        request.setEmail(EMAIL);
        request.setPhone("0901234567");
        request.setPassword(PASSWORD);
        return request;
    }

    private void savingGivesTheNewId() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(NEW_ID);
            return user;
        });
    }

    private AuditEntry recorded() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        return entry.getValue();
    }

    @Test
    void anAccountIsBuiltByTheServer_aHashedPasswordTheRoleUserAnEmptyWalletActive() {
        savingGivesTheNewId();

        User registered = service.register(request());

        assertEquals("newbie", registered.getUsername());
        assertEquals(EMAIL, registered.getEmail());
        assertEquals("0901234567", registered.getPhone());
        assertNotEquals(PASSWORD, registered.getPassword());
        assertTrue(passwordEncoder.matches(PASSWORD, registered.getPassword()));
        assertEquals("ROLE_USER", registered.getRole());
        assertEquals(0, BigDecimal.ZERO.compareTo(registered.getWalletBalance()));
        assertTrue(registered.isActive());
    }

    @Test
    void theNewAccountIsRecordedAsCreatedByItself_withoutItsPasswordOrHash_andAnnounced() {
        savingGivesTheNewId();

        User registered = service.register(request());

        AuditEntry entry = recorded();
        assertEquals(AuditAction.USER_CREATED, entry.action());
        assertEquals(AuditActor.of(registered), entry.actor());
        assertEquals(NEW_ID, entry.targetId());
        assertFalse(entry.description().contains(PASSWORD));
        assertFalse(entry.description().contains(registered.getPassword()));
        verify(eventPublisher).publishEvent(StatisticsChangedEvent.of(StatisticsChange.ACCOUNT_CREATED));
    }

    @Test
    void anAccountTheDatabaseRefusesIsNeitherRecordedNorAnnounced() {
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThrows(DataIntegrityViolationException.class, () -> service.register(request()));

        verifyNoInteractions(auditLogService, eventPublisher);
    }

    @Test
    void theAccountOfAFirstSocialSignInIsCreatedWithItsProvider_andRecorded() {
        savingGivesTheNewId();

        User registered = service.registerSocialAccount(EMAIL, "Google");

        assertEquals(EMAIL, registered.getUsername());
        assertEquals("ROLE_USER", registered.getRole());
        assertTrue(registered.getPhone().startsWith("GO_"), "a unique placeholder phone");
        AuditEntry entry = recorded();
        assertEquals(AuditAction.USER_CREATED, entry.action());
        assertEquals(AuditActor.of(registered), entry.actor());
        assertEquals(NEW_ID, entry.targetId());
        assertEquals("Tài khoản được tạo khi đăng nhập lần đầu qua google", entry.description());
        verify(eventPublisher).publishEvent(StatisticsChangedEvent.of(StatisticsChange.ACCOUNT_CREATED));
    }

    @Test
    void bothCreationsRunInAWritableTransaction_soTheAccountAndItsRowCommitTogether() throws NoSuchMethodException {
        for (var method : new java.lang.reflect.Method[] {
                AuthService.class.getMethod("register", RegisterRequestDto.class),
                AuthService.class.getMethod("registerSocialAccount", String.class, String.class)}) {
            Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
            assertNotNull(transactional, method.getName());
            assertFalse(transactional.readOnly(), method.getName());
        }
    }
}
