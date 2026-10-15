package vn.edu.huit.finance.core.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.SubscriptionPlan;
import vn.edu.huit.finance.core.Repository.SubscriptionPlanRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.util.NoSuchElementException;

/**
 * Membership plans, for administrators only (the caller checks that). Each change and its audit row, naming the
 * administrator, commit together. Only the plan's own fields are taken from what the caller sends: a new plan is always
 * a new row, active, whatever id or status came with it.
 */
@Slf4j
@Service
public class SubscriptionPlanService {

    private static final String MSG_PLAN_NOT_FOUND = "Không tìm thấy gói hội viên";
    private static final String AUDIT_CREATED = "Tạo gói hội viên \"%s\"";
    private static final String AUDIT_UPDATED = "Cập nhật thông tin gói \"%s\"";
    private static final String AUDIT_OPENED = "Mở bán gói \"%s\"";
    private static final String AUDIT_HIDDEN = "Tạm ẩn gói \"%s\"";
    private static final String AUDIT_DELETED = "Xóa vĩnh viễn gói \"%s\"";

    @Autowired
    private SubscriptionPlanRepository subscriptionPlanRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Transactional
    public SubscriptionPlan createPlan(SubscriptionPlan values) {
        SubscriptionPlan plan = new SubscriptionPlan();
        copyFields(values, plan);
        plan.setActive(true);
        SubscriptionPlan created = subscriptionPlanRepository.save(plan);

        record(AuditAction.PLAN_CREATED, created.getId(), String.format(AUDIT_CREATED, created.getPlanName()));
        log.info("Subscription plan created, planId={}", created.getId());
        return created;
    }

    @Transactional
    public SubscriptionPlan updatePlan(Long planId, SubscriptionPlan values) {
        SubscriptionPlan plan = findPlan(planId);
        copyFields(values, plan);
        SubscriptionPlan updated = subscriptionPlanRepository.save(plan);

        record(AuditAction.PLAN_UPDATED, planId, String.format(AUDIT_UPDATED, updated.getPlanName()));
        log.info("Subscription plan updated, planId={}", planId);
        return updated;
    }

    @Transactional
    public SubscriptionPlan togglePlan(Long planId) {
        SubscriptionPlan plan = findPlan(planId);
        plan.setActive(!plan.isActive());
        SubscriptionPlan toggled = subscriptionPlanRepository.save(plan);

        record(AuditAction.PLAN_STATUS_CHANGED, planId,
                String.format(toggled.isActive() ? AUDIT_OPENED : AUDIT_HIDDEN, toggled.getPlanName()));
        log.info("Subscription plan toggled, planId={}, active={}", planId, toggled.isActive());
        return toggled;
    }

    @Transactional
    public void deletePlan(Long planId) {
        SubscriptionPlan plan = findPlan(planId);
        subscriptionPlanRepository.delete(plan);

        record(AuditAction.PLAN_DELETED, planId, String.format(AUDIT_DELETED, plan.getPlanName()));
        log.info("Subscription plan deleted, planId={}", planId);
    }

    private SubscriptionPlan findPlan(Long planId) {
        return subscriptionPlanRepository.findById(planId).orElseThrow(() -> {
            log.warn("Subscription plan not found, planId={}", planId);
            return new NoSuchElementException(MSG_PLAN_NOT_FOUND);
        });
    }

    private static void copyFields(SubscriptionPlan from, SubscriptionPlan to) {
        to.setPlanName(from.getPlanName());
        to.setPrice(from.getPrice());
        to.setDurationDays(from.getDurationDays());
        to.setMaxAiScans(from.getMaxAiScans());
        to.setWcmSpadeEnabled(from.isWcmSpadeEnabled());
    }

    /** By the administrator who called, looked up when the row is written. */
    private void record(AuditAction action, Long planId, String description) {
        auditLogService.record(AuditEntry.of(action, AuditActor.currentCaller(), planId, description));
    }
}
