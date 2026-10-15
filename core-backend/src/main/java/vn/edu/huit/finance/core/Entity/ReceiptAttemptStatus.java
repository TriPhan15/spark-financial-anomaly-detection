package vn.edu.huit.finance.core.Entity;

/**
 * What one reading of a receipt concluded; {@code ReceiptProcessingAttempt.status} stores the name of one of these. A
 * reading is recorded once it has ended, in the database transaction that applies it to the receipt, so there is no value
 * for a reading in progress: one that did not end was rolled back with everything else and left no attempt behind.
 */
public enum ReceiptAttemptStatus {
    /** The AI's answer held a usable total; the receipt became COMPLETED_RAW with what it read. */
    COMPLETED,
    /** The AI's answer held no usable total; the error code of the attempt says why. */
    FAILED,
    /**
     * An answer a receipt held before attempts existed (Transaction.rawReceiptData, Receipt.aiRawData), imported as it was.
     * What that reading concluded was never recorded with it, so this status claims nothing about it.
     */
    LEGACY
}
