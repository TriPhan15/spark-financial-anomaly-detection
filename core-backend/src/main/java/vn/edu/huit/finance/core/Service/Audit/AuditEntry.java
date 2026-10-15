package vn.edu.huit.finance.core.Service.Audit;

import vn.edu.huit.finance.core.Entity.AuditAction;

import java.util.Objects;

/**
 * What one action_logs row will say. The kind of the target and the result follow from the action; the target id is the
 * id of the record the action changed (null when there is none). The description is a short, fixed text filled with
 * ids, statuses and, where the business needs them, amounts: never a password, hash, OTP, token, raw AI answer, request
 * body or exception message.
 */
public record AuditEntry(AuditAction action, AuditActor actor, Long targetId, String description) {

    public AuditEntry {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        if (targetId != null && action.getTargetType() == null) {
            throw new IllegalArgumentException(action + " is about no single record and takes no target id");
        }
    }

    public static AuditEntry of(AuditAction action, AuditActor actor, Long targetId, String description) {
        return new AuditEntry(action, actor, targetId, description);
    }
}
