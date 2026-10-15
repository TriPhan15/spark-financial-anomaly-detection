package vn.edu.huit.finance.core.Service;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Entity.ActionLog;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Exception.AuditLogWriteException;
import vn.edu.huit.finance.core.Exception.UserNotFoundException;
import vn.edu.huit.finance.core.Repository.ActionLogRepository;
import vn.edu.huit.finance.core.Security.ClientIp;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Audit.AuditText;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * The only writer of action_logs, the application activity log (not the Hibernate Envers history of the entities). Two
 * ways in, one per failure policy, chosen by what the row records and never by the caller's taste:
 * <ul>
 *   <li>{@link #record}: a change the request makes in the database. The row joins the transaction of the change and
 *       commits or rolls back with it; it fails fast when there is none. A row that cannot be written rolls the change
 *       back ({@link AuditLogWriteException}, a fixed message for the client): nothing changes without its row.</li>
 *   <li>{@link #recordIndependently}: what is not a database change of the request (sign-ins, refused requests, operations
 *       outside the database). The row is written in a transaction of its own, so it stays when the caller's transaction
 *       rolls back. Reached through {@link SecurityAuditEvent} and its listener, which decides what a failure means.</li>
 * </ul>
 * Both take the client address from the current request ({@link ClientIp}), the time from the clock, the kind of target and
 * the result from the action, and clean every text ({@link AuditText}). A write that fails is logged at ERROR with the
 * action, actor and target, counted in {@value #WRITE_FAILURES_METRIC}, and rethrown: never swallowed here.
 */
@Slf4j
@Service
public class AuditLogService {

    public static final String WRITE_FAILURES_METRIC = "audit.write.failures";
    private static final String MSG_WRITE_FAILED =
            "Không thể ghi nhật ký hệ thống, thao tác chưa được thực hiện. Vui lòng thử lại sau.";
    private static final String POLICY_TRANSACTIONAL = "transactional";
    private static final String POLICY_INDEPENDENT = "independent";

    @Autowired
    private ActionLogRepository actionLogRepository;

    @Autowired
    private CurrentUserProvider currentUserProvider;

    // Not injected: the system clock is what production uses; tests replace it to control the timestamp.
    private Clock clock = Clock.systemDefaultZone();

    /** Records a change made in the caller's transaction, as part of it. Requires that transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        write(entry, POLICY_TRANSACTIONAL);
    }

    /** Records something that is not a change of the caller's transaction, in a transaction of its own. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(AuditEntry entry) {
        write(entry, POLICY_INDEPENDENT);
    }

    private void write(AuditEntry entry, String policy) {
        AuditAction action = entry.action();
        AuditActor actor = resolve(entry.actor());
        boolean hasTarget = entry.targetId() != null;
        ActionLog row = ActionLog.builder()
                .userId(actor.userId())
                .userEmail(AuditText.clean(actor.label(), ActionLog.USER_EMAIL_MAX_LENGTH))
                .action(action.name())
                .targetType(hasTarget ? action.getTargetType().name() : null)
                .targetId(entry.targetId())
                .result(action.getResult().name())
                .description(AuditText.clean(entry.description(), ActionLog.DESCRIPTION_MAX_LENGTH))
                .ipAddress(ClientIp.ofCurrentRequest())
                .timestamp(LocalDateTime.now(clock))
                .build();
        try {
            // An IDENTITY id: the INSERT runs here, so a failure surfaces here and not at the commit.
            actionLogRepository.save(row);
        } catch (RuntimeException e) {
            Metrics.counter(WRITE_FAILURES_METRIC, "policy", policy, "action", action.name()).increment();
            log.error("AUDIT_WRITE_FAILED policy={} action={} actorId={} targetType={} targetId={}",
                    policy, action, actor.userId(), row.getTargetType(), row.getTargetId(), e);
            throw new AuditLogWriteException(MSG_WRITE_FAILED, e);
        }
    }

    /**
     * The actor as the row names it. The caller of the current request is looked up now, in the SecurityContext; when
     * nobody is signed in, the row names whatever principal the request carries (or nobody) and no account.
     */
    private AuditActor resolve(AuditActor actor) {
        if (!actor.isCurrentCaller()) {
            return actor;
        }
        try {
            return AuditActor.of(currentUserProvider.getCurrentUser());
        } catch (UserNotFoundException e) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            boolean anonymous = authentication == null || authentication instanceof AnonymousAuthenticationToken;
            return AuditActor.unauthenticated(anonymous ? null : authentication.getName());
        }
    }
}
