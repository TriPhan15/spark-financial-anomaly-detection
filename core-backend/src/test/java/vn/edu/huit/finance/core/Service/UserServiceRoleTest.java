package vn.edu.huit.finance.core.Service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.edu.huit.finance.core.Dto.UserCreateDto;
import vn.edu.huit.finance.core.Dto.UserUpdateDto;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.InvalidRoleException;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChange;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChangedEvent;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The ADMIN / ROLE_ADMIN mismatch: forms send "ADMIN", the database holds "ROLE_ADMIN", and both must mean the same. */
@ExtendWith(MockitoExtension.class)
class UserServiceRoleTest {

    private static final Long ID = 5L;

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private UserService service;

    private static User user(String role, boolean active) {
        User user = new User();
        user.setId(ID);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPhone("0900000001");
        user.setRole(role);
        user.setActive(active);
        return user;
    }

    private static UserCreateDto createDto(String role) {
        UserCreateDto dto = new UserCreateDto();
        dto.setUsername("new");
        dto.setEmail("new@example.com");
        dto.setPhone("0900000002");
        dto.setPassword("secret1");
        dto.setRole(role);
        return dto;
    }

    private static UserUpdateDto updateDto(String role) {
        UserUpdateDto dto = new UserUpdateDto();
        dto.setRole(role);
        return dto;
    }

    private User whenLoaded(User user) {
        when(userRepository.findById(ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return user;
    }

    // ---- createUser ----

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "admin", "ROLE_ADMIN", " role_admin "})
    void createUserStoresEverySpellingOfAdminAsRoleAdmin(String spelling) {
        when(passwordEncoder.encode("secret1")).thenReturn("hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User created = service.createUser(createDto(spelling));

        assertEquals("ROLE_ADMIN", created.getRole());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "user", "ROLE_USER"})
    void createUserStoresEverySpellingOfUserAsRoleUser(String spelling) {
        when(passwordEncoder.encode("secret1")).thenReturn("hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("ROLE_USER", service.createUser(createDto(spelling)).getRole());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "ROLE_"})
    void createUserRefusesARoleWithoutAName(String nothing) {
        assertThrows(InvalidRoleException.class, () -> service.createUser(createDto(nothing)));

        verify(userRepository, never()).save(any());
    }

    // ---- toggleUserStatus ----

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ADMIN", "admin", " role_admin "})
    void anAdminCannotBeLockedWhicheverWayTheRoleIsWritten(String storedRole) {
        User admin = user(storedRole, true);
        when(userRepository.findById(ID)).thenReturn(Optional.of(admin));

        RuntimeException refused = assertThrows(RuntimeException.class, () -> service.toggleUserStatus(ID));

        assertTrue(refused.getMessage().contains("ADMIN"));
        assertTrue(admin.isActive(), "the admin must stay active");
        verify(userRepository, never()).save(any());
    }

    @Test
    void anOrdinaryUserIsLockedAndThenUnlocked() {
        User alice = whenLoaded(user("ROLE_USER", true));

        service.toggleUserStatus(ID);
        assertFalse(alice.isActive());

        service.toggleUserStatus(ID);
        assertTrue(alice.isActive());
        verify(userRepository, times(2)).save(alice);
    }

    @Test
    void aLockedAdministratorAccountStaysProtectedToo() {
        User lockedAdmin = user("ROLE_ADMIN", false);
        when(userRepository.findById(ID)).thenReturn(Optional.of(lockedAdmin));

        assertThrows(RuntimeException.class, () -> service.toggleUserStatus(ID));

        assertFalse(lockedAdmin.isActive());
        verify(userRepository, never()).save(any());
    }

    @Test
    void aUserWithoutARoleIsTreatedAsAnOrdinaryUser() {
        User noRole = whenLoaded(user(null, true));

        service.toggleUserStatus(ID);

        assertFalse(noRole.isActive());
    }

    @Test
    void togglingAnUnknownUserSaysSo() {
        when(userRepository.findById(ID)).thenReturn(Optional.empty());

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.toggleUserStatus(ID));

        assertTrue(error.getMessage().contains(ID.toString()));
        verify(userRepository, never()).save(any());
    }

    // ---- updateUser: the administrator ----

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "admin", "ROLE_ADMIN"})
    void anAdministratorMayPromoteAUser(String spelling) {
        User alice = whenLoaded(user("ROLE_USER", true));

        service.updateUser(ID, updateDto(spelling));

        assertEquals("ROLE_ADMIN", alice.getRole());
    }

    @Test
    void anAdministratorMayDemoteAUser() {
        User bob = whenLoaded(user("ROLE_ADMIN", true));

        service.updateUser(ID, updateDto("user"));

        assertEquals("ROLE_USER", bob.getRole());
    }

    @Test
    void aBlankOrMissingRoleLeavesTheRoleAlone() {
        User alice = whenLoaded(user("ROLE_USER", true));

        service.updateUser(ID, updateDto(null));
        service.updateUser(ID, updateDto("  "));

        assertEquals("ROLE_USER", alice.getRole());
    }

    @Test
    void aLegacyRoleIsWrittenBackInItsCanonicalFormWhenAnAdministratorSavesIt() {
        User legacy = whenLoaded(user("USER", true));

        service.updateUser(ID, updateDto("user"));

        assertEquals("ROLE_USER", legacy.getRole());
    }

    @Test
    void updatingAnUnknownUserSaysSo() {
        when(userRepository.findById(ID)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.updateUser(ID, updateDto("ADMIN")));
    }

    // ---- updateOwnProfile: the user editing themselves ----

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "admin", "ROLE_ADMIN", " role_admin "})
    void aUserCannotPromoteThemselvesInAnySpelling(String spelling) {
        User alice = user("ROLE_USER", true);
        when(userRepository.findById(ID)).thenReturn(Optional.of(alice));

        assertThrows(AccessDeniedException.class, () -> service.updateOwnProfile(ID, updateDto(spelling)));

        assertEquals("ROLE_USER", alice.getRole());
        verify(userRepository, never()).save(any());
    }

    @Test
    void aRefusedRoleChangeAlsoKeepsTheOtherFieldsOfTheSameRequestFromBeingSaved() {
        User alice = user("ROLE_USER", true);
        when(userRepository.findById(ID)).thenReturn(Optional.of(alice));
        UserUpdateDto dto = updateDto("ADMIN");
        dto.setPhone("0911111111");

        assertThrows(AccessDeniedException.class, () -> service.updateOwnProfile(ID, dto));

        verify(userRepository, never()).save(any());
        assertEquals("0900000001", alice.getPhone(), "nothing of a refused request may be applied");
    }

    @Test
    void aUserCannotDemoteOrLeaveTheirRoleEither() {
        User admin = user("ROLE_ADMIN", true);
        when(userRepository.findById(ID)).thenReturn(Optional.of(admin));

        assertThrows(AccessDeniedException.class, () -> service.updateOwnProfile(ID, updateDto("USER")));

        assertEquals("ROLE_ADMIN", admin.getRole());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "user", "ROLE_USER", " role_user "})
    void repeatingTheCurrentRoleIsHarmless(String spelling) {
        User alice = whenLoaded(user("ROLE_USER", true));

        service.updateOwnProfile(ID, updateDto(spelling));

        assertEquals("ROLE_USER", alice.getRole());
        verify(userRepository).save(alice);
    }

    @Test
    void repeatingTheCurrentRoleWorksForALegacyStoredSpellingToo() {
        User legacy = whenLoaded(user("USER", true));

        service.updateOwnProfile(ID, updateDto("ROLE_USER"));

        verify(userRepository).save(legacy);
    }

    @Test
    void aUserStillEditsTheirOwnProfileFields() {
        User alice = whenLoaded(user("ROLE_USER", true));
        UserUpdateDto dto = new UserUpdateDto();
        dto.setUsername("alice2");
        dto.setPhone("0922222222");
        dto.setEmail("alice2@example.com");

        User saved = service.updateOwnProfile(ID, dto);

        assertEquals("alice2", saved.getUsername());
        assertEquals("0922222222", saved.getPhone());
        assertEquals("alice2@example.com", saved.getEmail());
        assertEquals("ROLE_USER", alice.getRole());
    }

    @Test
    void theAdministratorPathAndTheSelfServicePathShareTheFieldRules() {
        User alice = whenLoaded(user("ROLE_USER", true));
        UserUpdateDto blanks = new UserUpdateDto();
        blanks.setUsername(" ");
        blanks.setPhone("");

        service.updateOwnProfile(ID, blanks);
        service.updateUser(ID, blanks);

        assertEquals("alice", alice.getUsername());
        assertEquals("0900000001", alice.getPhone());
        verify(userRepository, times(2)).save(alice);
    }

    // ---- what each write reports to the statistics cache ----

    @Test
    void aNewAccountIsReportedForTheAdminFigures() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createUser(createDto("user"));

        verify(eventPublisher).publishEvent(StatisticsChangedEvent.of(StatisticsChange.ACCOUNT_CREATED));
    }

    @Test
    void anEditedProfileIsReportedWhoeverEditsIt() {
        whenLoaded(user("ROLE_USER", true));
        UserUpdateDto dto = new UserUpdateDto();
        dto.setUsername("alice2");

        service.updateOwnProfile(ID, dto);
        service.updateUser(ID, dto);

        verify(eventPublisher, times(2)).publishEvent(StatisticsChangedEvent.of(StatisticsChange.ACCOUNT_PROFILE, ID));
    }

    @Test
    void aRefusedProfileEditReportsNothing() {
        when(userRepository.findById(ID)).thenReturn(Optional.of(user("ROLE_USER", true)));

        assertThrows(AccessDeniedException.class, () -> service.updateOwnProfile(ID, updateDto("ADMIN")));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aDeletedAccountIsReportedWithItsId() {
        when(userRepository.findById(ID)).thenReturn(Optional.of(user("ROLE_USER", true)));

        service.deleteUser(ID);

        verify(eventPublisher).publishEvent(StatisticsChangedEvent.of(StatisticsChange.ACCOUNT_DELETED, ID));
    }

    @Test
    void aDeletionTheDatabaseRefusesReportsNothing() {
        when(userRepository.findById(ID)).thenReturn(Optional.of(user("ROLE_USER", true)));
        doThrow(new DataIntegrityViolationException("transactions reference the user")).when(userRepository).flush();

        assertThrows(RuntimeException.class, () -> service.deleteUser(ID));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void lockingAUserChangesNoCachedFigureAndReportsNothing() {
        whenLoaded(user("ROLE_USER", true));

        service.toggleUserStatus(ID);

        verifyNoInteractions(eventPublisher);
    }
}
