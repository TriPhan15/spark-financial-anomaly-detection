package vn.edu.huit.finance.core.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

/**
 * The saving goals of the signed-in caller. No method takes a user id: the owner is the caller, from the SecurityContext,
 * and a goal of somebody else is refused with a 403 (recorded as AUTH_ACCESS_DENIED). Each change and its audit row commit
 * together. The goals are figures the owner keeps for themselves (no money moves), so neither the amounts nor the title
 * are part of the rows.
 */
@Slf4j
@Service
public class SavingGoalService {

    private static final String MSG_GOAL_NOT_FOUND = "Không tìm thấy mục tiêu tiết kiệm";
    private static final String MSG_GOAL_FORBIDDEN = "Bạn không có quyền thao tác trên mục tiêu tiết kiệm này";
    private static final String AUDIT_CREATED = "Tạo mục tiêu tiết kiệm";
    private static final String AUDIT_UPDATED = "Cập nhật số tiền đã tiết kiệm";
    private static final String AUDIT_DELETED = "Xóa mục tiêu tiết kiệm";

    @Autowired
    private SavingGoalRepository savingGoalRepository;

    @Autowired
    private CurrentUserProvider currentUserProvider;

    @Autowired
    private AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<SavingGoalDto> getMySavings() {
        return savingGoalRepository.findByUserIdOrderByCreatedAtDesc(currentUserProvider.getCurrentUser().getId()).stream()
                .map(SavingGoalService::toDto)
                .toList();
    }

    @Transactional
    public SavingGoalDto createSaving(SavingGoalDto request) {
        User caller = currentUserProvider.getCurrentUser();
        SavingGoal goal = new SavingGoal();
        goal.setTitle(request.getTitle());
        goal.setTargetAmount(request.getTarget());
        goal.setCurrentAmount(request.getCurrent());
        goal.setDeadline(request.getDeadline());
        goal.setUser(caller);
        SavingGoal created = savingGoalRepository.save(goal);

        auditLogService.record(AuditEntry.of(AuditAction.SAVING_CREATED, AuditActor.of(caller), created.getId(), AUDIT_CREATED));
        log.info("Saving goal created, userId={}, goalId={}", caller.getId(), created.getId());
        return toDto(created);
    }

    @Transactional
    public void updateCurrentAmount(Long goalId, BigDecimal currentAmount) {
        User caller = currentUserProvider.getCurrentUser();
        SavingGoal goal = findOwnedGoal(goalId, caller);
        goal.setCurrentAmount(currentAmount);
        savingGoalRepository.save(goal);

        auditLogService.record(AuditEntry.of(AuditAction.SAVING_UPDATED, AuditActor.of(caller), goalId, AUDIT_UPDATED));
        log.info("Saving goal updated, userId={}, goalId={}", caller.getId(), goalId);
    }

    @Transactional
    public void deleteSaving(Long goalId) {
        User caller = currentUserProvider.getCurrentUser();
        savingGoalRepository.delete(findOwnedGoal(goalId, caller));

        auditLogService.record(AuditEntry.of(AuditAction.SAVING_DELETED, AuditActor.of(caller), goalId, AUDIT_DELETED));
        log.info("Saving goal deleted, userId={}, goalId={}", caller.getId(), goalId);
    }

    /** A goal that does not exist is refused like before (400), one that exists but is somebody else's with a 403. */
    private SavingGoal findOwnedGoal(Long goalId, User caller) {
        SavingGoal goal = savingGoalRepository.findById(goalId).orElseThrow(() -> {
            log.warn("Saving goal not found, userId={}, goalId={}", caller.getId(), goalId);
            return new NoSuchElementException(MSG_GOAL_NOT_FOUND);
        });
        if (!goal.getUser().getId().equals(caller.getId())) {
            log.warn("Saving goal access denied, userId={}, goalId={}", caller.getId(), goalId);
            throw new TransactionAccessDeniedException(MSG_GOAL_FORBIDDEN);
        }
        return goal;
    }

    private static SavingGoalDto toDto(SavingGoal goal) {
        return SavingGoalDto.builder()
                .id(goal.getId())
                .title(goal.getTitle())
                .target(goal.getTargetAmount())
                .current(goal.getCurrentAmount())
                .deadline(goal.getDeadline())
                .build();
    }
}
