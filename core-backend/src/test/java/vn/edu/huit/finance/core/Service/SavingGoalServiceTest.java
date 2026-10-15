package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.edu.huit.finance.core.Dto.SavingGoalDto;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.SavingGoal;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.TransactionAccessDeniedException;
import vn.edu.huit.finance.core.Repository.SavingGoalRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The caller's saving goals: the owner always comes from the SecurityContext, a goal of somebody else is refused with a
 * 403 and records no change, and each change of the caller's own goal is recorded with the caller and the goal.
 */
@ExtendWith(MockitoExtension.class)
class SavingGoalServiceTest {

    @Mock private SavingGoalRepository repository;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private SavingGoalService service;

    private final User alice = user(1L);
    private final User bob = user(2L);

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@example.com");
        return user;
    }

    @BeforeEach
    void aliceIsTheCaller() {
        when(currentUserProvider.getCurrentUser()).thenReturn(alice);
    }

    private SavingGoal goalOf(User owner) {
        SavingGoal goal = new SavingGoal();
        goal.setId(6L);
        goal.setTitle("Mua xe");
        goal.setTargetAmount(new BigDecimal("50000000"));
        goal.setCurrentAmount(new BigDecimal("1000000"));
        goal.setUser(owner);
        when(repository.findById(6L)).thenReturn(Optional.of(goal));
        return goal;
    }

    private AuditEntry recorded() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        return entry.getValue();
    }

    private static void assertNoFigureOrTitleIn(AuditEntry entry) {
        assertFalse(entry.description().contains("Mua xe"));
        assertFalse(entry.description().contains("50000000"));
    }

    @Test
    void aNewGoalBelongsToTheCaller_andIsRecorded() {
        when(repository.save(any(SavingGoal.class))).thenAnswer(invocation -> {
            SavingGoal saved = invocation.getArgument(0);
            saved.setId(6L);
            return saved;
        });

        service.createSaving(SavingGoalDto.builder().title("Mua xe").target(new BigDecimal("50000000"))
                .current(BigDecimal.ZERO).build());

        ArgumentCaptor<SavingGoal> saved = ArgumentCaptor.forClass(SavingGoal.class);
        verify(repository).save(saved.capture());
        assertSame(alice, saved.getValue().getUser());
        AuditEntry entry = recorded();
        assertEquals(AuditAction.SAVING_CREATED, entry.action());
        assertEquals(AuditActor.of(alice), entry.actor());
        assertEquals(6L, entry.targetId());
        assertNoFigureOrTitleIn(entry);
    }

    @Test
    void anUpdateAndADeletionOfTheCallersGoalAreRecorded() {
        SavingGoal goal = goalOf(alice);

        service.updateCurrentAmount(6L, new BigDecimal("2000000"));
        service.deleteSaving(6L);

        assertEquals(0, new BigDecimal("2000000").compareTo(goal.getCurrentAmount()));
        verify(repository).delete(goal);
        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService, times(2)).record(entries.capture());
        assertEquals(List.of(AuditAction.SAVING_UPDATED, AuditAction.SAVING_DELETED),
                entries.getAllValues().stream().map(AuditEntry::action).toList());
        entries.getAllValues().forEach(entry -> {
            assertEquals(AuditActor.of(alice), entry.actor());
            assertEquals(6L, entry.targetId());
            assertNoFigureOrTitleIn(entry);
        });
    }

    @Test
    void aGoalOfSomebodyElseIsRefusedWith403_unchanged_andNoChangeIsRecorded() {
        SavingGoal bobsGoal = goalOf(bob);

        assertThrows(TransactionAccessDeniedException.class, () -> service.updateCurrentAmount(6L, BigDecimal.ONE));
        assertThrows(TransactionAccessDeniedException.class, () -> service.deleteSaving(6L));

        assertEquals(0, new BigDecimal("1000000").compareTo(bobsGoal.getCurrentAmount()));
        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void aGoalThatDoesNotExistIsRefused_andNothingIsRecorded() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        NoSuchElementException refused = assertThrows(NoSuchElementException.class, () -> service.deleteSaving(99L));

        assertEquals("Không tìm thấy mục tiêu tiết kiệm", refused.getMessage());
        verifyNoInteractions(auditLogService);
    }
}
