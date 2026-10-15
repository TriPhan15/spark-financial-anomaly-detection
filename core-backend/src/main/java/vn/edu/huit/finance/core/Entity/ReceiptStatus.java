package vn.edu.huit.finance.core.Entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lifecycle of a receipt; {@code Receipt.status} stores the name of one of these. The column stays a text column, so the
 * rows written before the lifecycle existed keep working: they are read through {@link #fromStored}, which also knows the
 * one name that was retired ({@link #LEGACY_SUCCESS}). The lifecycle only says which moves exist; who may make a move
 * (the owner, an administrator) is decided by the service that makes it.
 */
public enum ReceiptStatus {
    /**
     * Created, no AI result recorded yet. The checkout of the mobile app never stores it (the AI has answered before the
     * receipt is created, so the receipt is inserted with the outcome), so it is found only in rows that predate the
     * lifecycle or were written by hand.
     */
    PENDING,
    /**
     * Reserved for a reading that core-backend itself would run: claim the receipt, call the AI outside any database
     * transaction, then record the outcome, with a way to recover a claim whose request died. No such flow exists: the AI
     * runs on the mobile app and its answer arrives complete in one request, so a reading goes from PENDING or FAILED
     * straight to its outcome. No move leads here and none leads away (a row can only hold it if written by hand); the
     * name is kept so that such a row is still recognised, and because the mobile app knows it.
     */
    PROCESSING,
    /** The AI read a usable total: the receipt holds exactly what the AI read. Stored as "SUCCESS" before the lifecycle. */
    COMPLETED_RAW,
    /** The owner corrected what the AI read; the AI's own answers are still kept, untouched, in the receipt's attempts. */
    EDITED,
    /** The AI read no usable total, or an administrator rejected the receipt; {@code Receipt.errorCode} says which. */
    FAILED,
    /** The owner of a FAILED receipt sent the amount and waits for an administrator. */
    WAITING_FOR_REVIEW,
    /** An administrator verified the receipt: its total is the amount of the transaction that carries it. */
    APPROVED,
    /** Withdrawn by the owner before it was confirmed. Final. */
    CANCELED;

    /**
     * What a read receipt was called before the lifecycle told COMPLETED_RAW, EDITED and APPROVED apart. Never written
     * any more, but still the text of older rows (read as COMPLETED_RAW, see {@link #fromStored}) and still the name the
     * clients that predate the lifecycle expect for a read receipt (see {@link #legacyName}).
     */
    public static final String LEGACY_SUCCESS = "SUCCESS";

    private static final Map<ReceiptStatus, Set<ReceiptStatus>> MOVES = new EnumMap<>(ReceiptStatus.class);

    static {
        // Reading: the answer of the AI for a first scan (PENDING, at the checkout) or for a retry (FAILED) is recorded in
        // the request that delivers it, and the receipt goes straight to COMPLETED_RAW, or to FAILED when the answer held
        // no usable total (a retry that still finds none stays FAILED, with the reason of the new reading).
        // Owner: corrects a read receipt, sends the amount of a failed one, withdraws a pending one.
        // Administrator: approves anything that is settled (again, too: that reconciles again), rejects anything not final.
        MOVES.put(PENDING, EnumSet.of(COMPLETED_RAW, FAILED, CANCELED, APPROVED));
        MOVES.put(PROCESSING, EnumSet.noneOf(ReceiptStatus.class));
        MOVES.put(COMPLETED_RAW, EnumSet.of(EDITED, APPROVED, FAILED));
        MOVES.put(EDITED, EnumSet.of(EDITED, APPROVED, FAILED));
        MOVES.put(FAILED, EnumSet.of(COMPLETED_RAW, FAILED, WAITING_FOR_REVIEW, APPROVED));
        MOVES.put(WAITING_FOR_REVIEW, EnumSet.of(APPROVED, FAILED));
        MOVES.put(APPROVED, EnumSet.of(APPROVED, FAILED));
        MOVES.put(CANCELED, EnumSet.noneOf(ReceiptStatus.class));
    }

    /**
     * The status a stored text stands for, whatever its letter case ("pending", "Failed": older rows were written by
     * hand), with "SUCCESS" read as COMPLETED_RAW. Empty for null and for a text that is none of the statuses.
     */
    public static Optional<ReceiptStatus> fromStored(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        // Locale.ROOT: in a Turkish default locale "failed".toUpperCase() would contain a dotted capital I.
        String name = stored.trim().toUpperCase(Locale.ROOT);
        if (LEGACY_SUCCESS.equals(name)) {
            return Optional.of(COMPLETED_RAW);
        }
        for (ReceiptStatus status : values()) {
            if (status.name().equals(name)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    /** True when the stored text stands for this status (see {@link #fromStored}). */
    public boolean matches(String status) {
        return fromStored(status).filter(this::equals).isPresent();
    }

    /** True when the lifecycle knows a move from this status to {@code target}. */
    public boolean canTransitionTo(ReceiptStatus target) {
        return MOVES.get(this).contains(target);
    }

    /**
     * The name this status has for the clients that predate the lifecycle (the mobile app reads it as the receipt status
     * of a transaction, the admin pages count receipts by it): they know SUCCESS for a receipt that was read and nothing
     * of how it got further, so COMPLETED_RAW, EDITED and APPROVED are all SUCCESS to them.
     */
    public String legacyName() {
        return switch (this) {
            case COMPLETED_RAW, EDITED, APPROVED -> LEGACY_SUCCESS;
            default -> name();
        };
    }

    /** {@link #legacyName} of a stored status; a text the lifecycle does not know is passed on as it is. */
    public static String legacyNameOf(String stored) {
        return fromStored(stored).map(ReceiptStatus::legacyName).orElse(stored);
    }
}
