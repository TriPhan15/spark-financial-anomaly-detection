package vn.edu.huit.finance.core.Service.Audit;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.edu.huit.finance.core.Exception.AuditLogWriteException;
import vn.edu.huit.finance.core.Service.AuditLogService;

/**
 * Writes the row a {@link SecurityAuditEvent} asks for, in a transaction of its own and right away (synchronously, in the
 * publisher's thread): the publisher may be inside a transaction that is about to roll back (a refused refresh), and the
 * row must stay; the request, its client address and its SecurityContext are still at hand.
 * <p>
 * Failure policy: a row that cannot be written does not change the answer to the request it describes (a refusal stays
 * a refusal, a sign-in goes on). The failure is never silent: it is logged at ERROR with the action and target (the
 * cause too, unless AuditLogService has logged it already) and counted in the metric {@value #DROPPED_EVENTS_METRIC}. The
 * row is not retried.
 */
@Slf4j
@Component
public class SecurityAuditListener {

    public static final String DROPPED_EVENTS_METRIC = "audit.events.dropped";

    @Autowired
    private AuditLogService auditLogService;

    @EventListener
    public void record(SecurityAuditEvent event) {
        AuditEntry entry = event.entry();
        try {
            auditLogService.recordIndependently(entry);
        } catch (AuditLogWriteException e) {
            dropped(entry, null);
        } catch (RuntimeException e) {
            // Failed before the insert (no connection for the new transaction, the caller could not be looked up...).
            dropped(entry, e);
        }
    }

    private void dropped(AuditEntry entry, RuntimeException cause) {
        Metrics.counter(DROPPED_EVENTS_METRIC, "action", entry.action().name()).increment();
        log.error("AUDIT_EVENT_DROPPED action={} targetId={}: the request is answered as if it had been recorded",
                entry.action(), entry.targetId(), cause);
    }
}
