package vn.edu.huit.finance.core.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.ExtractedReceiptDto;
import vn.edu.huit.finance.core.Dto.ReceiptAttemptResponseDto;
import vn.edu.huit.finance.core.Dto.ReceiptResponseDto;
import vn.edu.huit.finance.core.Dto.ReceiptRetryRequestDto;
import vn.edu.huit.finance.core.Dto.ReceiptUpdateRequestDto;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.ReceiptErrorCode;
import vn.edu.huit.finance.core.Entity.ReceiptProcessingAttempt;
import vn.edu.huit.finance.core.Entity.ReceiptStatus;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.ReceiptConflictException;
import vn.edu.huit.finance.core.Exception.ReceiptNotFoundException;
import vn.edu.huit.finance.core.Exception.TransactionAccessDeniedException;
import vn.edu.huit.finance.core.Repository.ReceiptProcessingAttemptRepository;
import vn.edu.huit.finance.core.Repository.ReceiptRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChange;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChangedEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The life of a receipt after the AI has read it. The AI runs on the mobile app (ai-service, POST /donut/scan): its answer
 * reaches core-backend complete, as {@code rawReceiptData}, with a checkout or with a retry, and core-backend never calls
 * the AI itself. So a reading is recorded by the one request that delivers it, in one database transaction that never
 * waits for a network: the receipt goes from PENDING (checkout) or FAILED (retry) straight to the outcome, COMPLETED_RAW or
 * FAILED, and is never stored as anything in between. There is no PROCESSING here, because nothing is ever in progress
 * that another request could see.
 * <p>
 * Every reading is kept as a {@link ReceiptProcessingAttempt}: the answer of the AI exactly as it arrived and what was
 * concluded from it, numbered per receipt, written once and never changed. A retry adds an attempt and replaces none. The
 * final fields of the receipt start as what the latest reading found; the owner's edits and the administrators' decisions
 * change those fields only, never an attempt.
 * <p>
 * A receipt is owned through the transaction that carries it (the entity has no owner of its own), so every rule here
 * works from that link, and a receipt no transaction carries can be acted on by nobody but an administrator:
 * <ul>
 *   <li>{@link #attachReceipt} builds the receipt of a transaction that is being created, with the attempt of its first
 *       reading; both are persisted by the cascade of that transaction, in the same database transaction as the wallet,
 *       so all of them commit or none does. A reading without a usable total still leaves a receipt behind, FAILED, for
 *       its owner to retry;</li>
 *   <li>{@link #editMyReceipt} lets the owner correct the final fields of a receipt that was read;</li>
 *   <li>{@link #retryMyReceipt} gives a FAILED receipt a new reading;</li>
 *   <li>{@link #submitFeedback} lets the owner send the amount of a FAILED receipt for an administrator to review;</li>
 *   <li>{@link #rejectReceipt} lets an administrator reject one, and {@link #getAttemptsOf} shows an administrator every
 *       reading of one.</li>
 * </ul>
 * None of them creates a transaction or moves a wallet. Approving a receipt does move the wallet, so it lives next to the
 * wallet lock, in {@code TransactionService.approveReceipt} (which this class cannot call: {@code TransactionService}
 * already depends on it).
 * <p>
 * Every change is recorded in action_logs inside its own database transaction, with the caller (the owner, or the
 * administrator who rejected) and the receipt: its creation, each reading of the AI (succeeded or failed, by attempt
 * number), an edit, a retry, a feedback and a rejection. What the AI answered is never part of a row.
 */
@Slf4j
@Service
public class ReceiptProcessingService {

    private static final String MSG_RECEIPT_NOT_FOUND = "Không tìm thấy hóa đơn";
    private static final String MSG_RECEIPT_FORBIDDEN = "Bạn không có quyền thao tác trên hóa đơn này";
    private static final String MSG_FEEDBACK_NOT_FAILED = "Chỉ có thể gửi yêu cầu xem xét cho các hóa đơn bị lỗi (FAILED)";
    private static final String MSG_AMOUNT_INVALID = "Số tiền điều chỉnh phải lớn hơn 0";
    private static final String MSG_DATE_INVALID = "Ngày trên hóa đơn không hợp lệ";
    private static final String MSG_EDIT_NOT_ALLOWED = "Chỉ có thể sửa hóa đơn đã được AI đọc xong và chưa được duyệt";
    private static final String MSG_RETRY_NOT_FAILED = "Chỉ có thể thử lại hóa đơn đang ở trạng thái lỗi (FAILED)";
    private static final String MSG_RECEIPT_CHANGED = "Hóa đơn vừa được thay đổi bởi một yêu cầu khác, vui lòng tải lại rồi thử lại";
    private static final String MSG_REJECT_NOT_ALLOWED = "Không thể từ chối hóa đơn đang được xử lý hoặc đã bị hủy";
    private static final String AUDIT_CREATED = "Hóa đơn mới của giao dịch #%d";
    private static final String AUDIT_READING_SUCCEEDED = "Lần đọc #%d: AI đọc được tổng tiền, trạng thái %s";
    private static final String AUDIT_READING_FAILED = "Lần đọc #%d: AI không đọc được tổng tiền (%s), trạng thái %s";
    private static final String AUDIT_EDITED = "Sửa hóa đơn (%s): %s → %s, phiên bản %d";
    private static final String AUDIT_FIELD_VENDOR = "tên cửa hàng";
    private static final String AUDIT_FIELD_TOTAL = "tổng tiền";
    private static final String AUDIT_FIELD_DATE = "ngày trên hóa đơn";
    private static final String AUDIT_NO_FIELD_CHANGED = "không có trường nào thay đổi";
    private static final String AUDIT_RETRIED = "Đọc lại hóa đơn (lần đọc #%d)";
    private static final String AUDIT_FEEDBACK = "Gửi số tiền đề nghị %s VNĐ để quản trị viên duyệt: %s → %s";
    private static final String AUDIT_REJECTED = "Từ chối hóa đơn: %s → %s (%s), %s";
    private static final String AUDIT_OWNER = "chủ sở hữu #%d";
    private static final String AUDIT_NO_OWNER = "không thuộc giao dịch nào";
    private static final int MONEY_SCALE = 2;

    @Autowired
    private ReceiptRepository receiptRepo;
    @Autowired
    private ReceiptProcessingAttemptRepository attemptRepo;
    @Autowired
    private CurrentUserProvider currentUserProvider;
    @Autowired
    private OcrService ocrService;
    @Autowired
    private AuditLogService auditLogService;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * Builds the receipt of {@code transaction} from the AI's JSON and links both ways. Meant to be called while the
     * transaction is being built inside {@code TransactionService.createTransaction}, which saves it (and, by cascade,
     * the receipt and the attempt of its first reading) after moving the wallet. The JSON is kept in that attempt as it
     * arrived, and the final fields start as what it says. Fallbacks for what the AI did not deliver: no timestamp keeps
     * the date the client sent, no address keeps the client's location, no seller leaves the vendor empty, and no total
     * marks the receipt FAILED (the AI could not read it) so that its owner can retry it or send the amount through
     * {@link #submitFeedback}.
     */
    public void attachReceipt(Transaction transaction, String rawReceiptData, String imageUrl) {
        Receipt receipt = new Receipt();
        if (imageUrl != null && !imageUrl.isBlank()) {
            receipt.setImageUrl(imageUrl.trim());
        }

        // The AI answered on the phone before the checkout was sent, so the new receipt takes the outcome of that reading
        // right here and is inserted with it: it is never stored as PENDING, nor as anything in between.
        ExtractedReceiptDto extracted = ocrService.extract(rawReceiptData);
        receipt.addFirstAttempt(recordReading(receipt, extracted, ReceiptProcessingAttempt.FIRST_ATTEMPT_NUMBER,
                rawReceiptData, null));
        if (extracted.getTimestamp() != null) {
            transaction.setTransactionDate(extracted.getTimestamp());
        }
        if (extracted.getAddress() != null) {
            transaction.setLocation(extracted.getAddress());
        }

        receipt.setTransaction(transaction);
        transaction.setReceipt(receipt);
        log.info("Receipt attached to a new transaction, status={}, errorCode={}, attempt={}",
                receipt.getStatus(), receipt.getErrorCode(), ReceiptProcessingAttempt.FIRST_ATTEMPT_NUMBER);
    }

    /**
     * Records a receipt {@link #attachReceipt} built, once the checkout has saved it (it has an id only then), in the
     * checkout's transaction: RECEIPT_CREATED, and the outcome of its first reading.
     */
    public void recordAttachedReceipt(Receipt receipt, AuditActor actor) {
        auditLogService.record(AuditEntry.of(AuditAction.RECEIPT_CREATED, actor, receipt.getId(),
                String.format(AUDIT_CREATED, receipt.getTransaction().getId())));
        recordReadingOutcome(receipt, ReceiptProcessingAttempt.FIRST_ATTEMPT_NUMBER, actor);
    }

    /** RECEIPT_PROCESSING_SUCCEEDED or _FAILED for the reading just recorded: the attempt, the reason, the new status. */
    private void recordReadingOutcome(Receipt receipt, int attemptNumber, AuditActor actor) {
        boolean read = ReceiptStatus.COMPLETED_RAW.matches(receipt.getStatus());
        AuditAction outcome = read ? AuditAction.RECEIPT_PROCESSING_SUCCEEDED : AuditAction.RECEIPT_PROCESSING_FAILED;
        String description = read
                ? String.format(AUDIT_READING_SUCCEEDED, attemptNumber, receipt.getStatus())
                : String.format(AUDIT_READING_FAILED, attemptNumber, receipt.getErrorCode(), receipt.getStatus());
        auditLogService.record(AuditEntry.of(outcome, actor, receipt.getId(), description));
    }

    /** One of the caller's receipts, by its own id. */
    @Transactional(readOnly = true)
    public ReceiptResponseDto getMyReceipt(Long receiptId) {
        return ReceiptResponseDto.from(findOwnedReceipt(receiptId, currentUserProvider.getCurrentUser().getId()));
    }

    /** The receipt one of the caller's transactions carries: how a client that only knows the transaction finds it. */
    @Transactional(readOnly = true)
    public ReceiptResponseDto getMyReceiptOfTransaction(Long transactionId) {
        Long callerId = currentUserProvider.getCurrentUser().getId();
        Receipt receipt = receiptRepo.findDetailedByTransactionId(transactionId).orElseThrow(() -> {
            log.warn("No receipt found for the transaction, userId={}, transactionId={}", callerId, transactionId);
            return new ReceiptNotFoundException(MSG_RECEIPT_NOT_FOUND);
        });
        requireOwner(receipt, callerId);
        return ReceiptResponseDto.from(receipt);
    }

    /**
     * The owner corrects the final fields of a receipt the AI has read (COMPLETED_RAW, or EDITED already); the receipt is
     * EDITED afterwards. Only the fields the request carries change, and no attempt (what the AI answered) is among them.
     * The request names the version it was made from: an edit made from an older one is refused, and so is the slower of
     * two edits that were made from the same one. Neither the transaction nor the wallet changes; an administrator's
     * approval is what carries a corrected total over to them.
     */
    @Transactional
    public ReceiptResponseDto editMyReceipt(Long receiptId, ReceiptUpdateRequestDto request) {
        BigDecimal total = request.getExtractedTotal() == null ? null : validAmount(request.getExtractedTotal());
        LocalDate date = validReceiptDate(request.getExtractedDate());

        User caller = currentUserProvider.getCurrentUser();
        Receipt receipt = findOwnedReceipt(receiptId, caller.getId());
        if (!Objects.equals(request.getVersion(), receipt.getVersion())) {
            log.warn("Receipt edit refused, it was made from an old version, userId={}, receiptId={}, sentVersion={}, version={}",
                    caller.getId(), receiptId, request.getVersion(), receipt.getVersion());
            throw new ReceiptConflictException(MSG_RECEIPT_CHANGED);
        }
        if (!receipt.canMoveTo(ReceiptStatus.EDITED)) {
            log.warn("Receipt edit refused, userId={}, receiptId={}, status={}", caller.getId(), receiptId, receipt.getStatus());
            throw new ReceiptConflictException(MSG_EDIT_NOT_ALLOWED);
        }

        String previousStatus = receipt.getStatus();
        List<String> changedFields = new ArrayList<>();
        if (request.getVendorName() != null) {
            String vendorName = request.getVendorName().isBlank() ? null : request.getVendorName().trim();
            addIfChanged(changedFields, AUDIT_FIELD_VENDOR, receipt.getVendorName(), vendorName);
            receipt.setVendorName(vendorName);
        }
        if (total != null) {
            if (receipt.getExtractedTotal() == null || receipt.getExtractedTotal().compareTo(total) != 0) {
                changedFields.add(AUDIT_FIELD_TOTAL);
            }
            receipt.setExtractedTotal(total);
        }
        if (date != null) {
            addIfChanged(changedFields, AUDIT_FIELD_DATE, receipt.getExtractedDate(), date);
            receipt.setExtractedDate(date);
        }
        receipt.moveTo(ReceiptStatus.EDITED);
        // Written now, not at the commit: a change another request committed in the meantime fails here, before anything
        // is reported, and the answer carries the version the row has after this edit.
        Receipt saved = receiptRepo.saveAndFlush(receipt);

        auditLogService.record(AuditEntry.of(AuditAction.RECEIPT_UPDATED, AuditActor.of(caller), receiptId,
                String.format(AUDIT_EDITED, changedFields.isEmpty() ? AUDIT_NO_FIELD_CHANGED : String.join(", ", changedFields),
                        previousStatus, saved.getStatus(), saved.getVersion())));
        eventPublisher.publishEvent(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_CONTENT, caller.getId()));
        log.info("Receipt edited, userId={}, receiptId={}, status={} -> {}, version={}",
                caller.getId(), receiptId, previousStatus, saved.getStatus(), saved.getVersion());
        return ReceiptResponseDto.from(saved);
    }

    /**
     * The owner gives a FAILED receipt a new reading: the answer of the AI for a new scan, which the client made. The same
     * receipt is updated, none is created, neither the transaction nor the wallet is touched, and the reading is added to
     * the receipt's attempts: the earlier ones stay exactly as they were. The receipt becomes COMPLETED_RAW, or stays
     * FAILED with the reason of this reading when it still holds no usable total.
     * <p>
     * Two retries that arrive together: the receipt is claimed first with a conditional update (still FAILED, still at the
     * version that was read), which locks its row until this request ends, so one of them is recorded and the other is
     * refused with a 409 once the first has committed (or recorded after all, if the first rolled back). Everything is one
     * database transaction that waits for no network: a failure anywhere leaves the receipt FAILED as it was, at its old
     * version, and no attempt behind.
     * <p>
     * A request that carries the key of a retry the receipt has recorded already (long ago, or by a request that committed
     * while this one waited for the row) is a repeat: it is answered with the receipt as it is now, and nothing is read or
     * written again. A request without a key is never taken for a repeat.
     */
    @Transactional
    public ReceiptResponseDto retryMyReceipt(Long receiptId, ReceiptRetryRequestDto request) {
        User caller = currentUserProvider.getCurrentUser();
        Receipt receipt = findOwnedReceipt(receiptId, caller.getId());
        String clientId = TransactionService.normalizeClientId(request.getClientId());
        if (wasRecordedBefore(receiptId, clientId)) {
            log.info("Repeated retry answered with the receipt as it is, userId={}, receiptId={}", caller.getId(), receiptId);
            return ReceiptResponseDto.from(receipt);
        }
        if (!ReceiptStatus.FAILED.matches(receipt.getStatus())) {
            log.warn("Receipt retry refused, userId={}, receiptId={}, status={}", caller.getId(), receiptId, receipt.getStatus());
            throw new ReceiptConflictException(MSG_RETRY_NOT_FAILED);
        }

        int claimed = receiptRepo.claimFailedForRetry(receiptId, receipt.getVersion());
        // The claim cleared the persistence context, so this is the row as it is now, whoever changed it last.
        Receipt current = findOwnedReceipt(receiptId, caller.getId());
        if (claimed == 0) {
            if (wasRecordedBefore(receiptId, clientId)) {
                log.info("Repeated retry arrived together with the first, answered with its result, userId={}, receiptId={}",
                        caller.getId(), receiptId);
                return ReceiptResponseDto.from(current);
            }
            log.warn("Receipt retry refused, another request changed the receipt first, userId={}, receiptId={}, status={}",
                    caller.getId(), receiptId, current.getStatus());
            throw new ReceiptConflictException(MSG_RECEIPT_CHANGED);
        }

        // This request holds the row from here on: no other retry, edit or approval of the receipt writes before it ends.
        int attemptNumber = keepLegacyReadings(current) + 1;
        attemptRepo.save(recordReading(current, ocrService.extract(request.getRawReceiptData()), attemptNumber,
                request.getRawReceiptData(), clientId));
        Receipt saved = receiptRepo.saveAndFlush(current);

        AuditActor actor = AuditActor.of(caller);
        auditLogService.record(AuditEntry.of(AuditAction.RECEIPT_RETRIED, actor, receiptId,
                String.format(AUDIT_RETRIED, attemptNumber)));
        recordReadingOutcome(saved, attemptNumber, actor);
        eventPublisher.publishEvent(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_CONTENT, caller.getId()));
        log.info("Receipt retried, userId={}, receiptId={}, attempt={}, status={} -> {}, errorCode={}, version={}",
                caller.getId(), receiptId, attemptNumber, ReceiptStatus.FAILED, saved.getStatus(),
                saved.getErrorCode(), saved.getVersion());
        return ReceiptResponseDto.from(saved);
    }

    /**
     * The owner of a FAILED receipt sends the amount the AI could not read; the receipt then waits for an administrator.
     * Anybody else, and anybody at all for a receipt no transaction carries, is refused with a 403.
     */
    @Transactional
    public void submitFeedback(Long receiptId, BigDecimal manualAmount) {
        BigDecimal amount = validAmount(manualAmount);

        User caller = currentUserProvider.getCurrentUser();
        Receipt receipt = findOwnedReceipt(receiptId, caller.getId());
        if (!ReceiptStatus.FAILED.matches(receipt.getStatus())) {
            throw new IllegalStateException(MSG_FEEDBACK_NOT_FAILED);
        }

        receipt.setExtractedTotal(amount);
        receipt.moveTo(ReceiptStatus.WAITING_FOR_REVIEW);
        receiptRepo.save(receipt);
        eventPublisher.publishEvent(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_STATUS, caller.getId()));
        // The amount is what the administrator will approve the wallet with, so it is part of the record.
        auditLogService.record(AuditEntry.of(AuditAction.RECEIPT_FEEDBACK_SUBMITTED, AuditActor.of(caller), receiptId,
                String.format(AUDIT_FEEDBACK, amount.toPlainString(), ReceiptStatus.FAILED, receipt.getStatus())));
        log.info("Receipt feedback received, userId={}, receiptId={}, status={} -> {}",
                caller.getId(), receiptId, ReceiptStatus.FAILED, receipt.getStatus());
    }

    /**
     * For administrators only (the caller of this method has to check that): rejects a receipt (FAILED, with the reason),
     * after which its owner may retry it or send the right amount through {@link #submitFeedback}. Neither the transaction
     * nor the wallet changes, and neither does any attempt. Rejecting a receipt that is FAILED already changes nothing;
     * one that was withdrawn by its owner (or holds the reserved PROCESSING, which nothing writes) cannot be rejected.
     */
    @Transactional
    public void rejectReceipt(Long receiptId) {
        Receipt receipt = receiptRepo.findDetailedById(receiptId).orElseThrow(() -> {
            log.warn("Receipt not found for rejection, receiptId={}", receiptId);
            return new ReceiptNotFoundException(MSG_RECEIPT_NOT_FOUND);
        });
        if (ReceiptStatus.FAILED.matches(receipt.getStatus())) {
            log.info("Receipt is FAILED already, nothing to reject, receiptId={}", receiptId);
            return;
        }
        if (!receipt.canMoveTo(ReceiptStatus.FAILED)) {
            log.warn("Receipt rejection refused, receiptId={}, status={}", receiptId, receipt.getStatus());
            throw new IllegalStateException(MSG_REJECT_NOT_ALLOWED);
        }

        String previousStatus = receipt.getStatus();
        receipt.failWith(ReceiptErrorCode.REJECTED_BY_ADMIN);
        receiptRepo.save(receipt);

        // A receipt no transaction carries belongs to nobody: only the admin summary counts it.
        Transaction carrier = receipt.getTransaction();
        Long ownerId = carrier != null ? carrier.getUser().getId() : null;
        eventPublisher.publishEvent(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_STATUS, ownerId));
        // The administrator is the caller of this request, looked up when the row is written.
        auditLogService.record(AuditEntry.of(AuditAction.RECEIPT_REJECTED, AuditActor.currentCaller(), receiptId,
                String.format(AUDIT_REJECTED, previousStatus, receipt.getStatus(), receipt.getErrorCode(),
                        ownerId != null ? String.format(AUDIT_OWNER, ownerId) : AUDIT_NO_OWNER)));
        log.info("Receipt rejected, receiptId={}, ownerId={}, status={} -> {}", receiptId, ownerId, previousStatus, receipt.getStatus());
    }

    /**
     * For administrators only (the caller of this method has to check that): every reading of a receipt, oldest first,
     * with what the AI answered each time. A receipt that does not exist is a 404; one that has no reading recorded (it
     * predates attempts and the migration has not imported its answer yet) has none.
     */
    @Transactional(readOnly = true)
    public List<ReceiptAttemptResponseDto> getAttemptsOf(Long receiptId) {
        if (!receiptRepo.existsById(receiptId)) {
            log.warn("Receipt not found for its attempts, receiptId={}", receiptId);
            throw new ReceiptNotFoundException(MSG_RECEIPT_NOT_FOUND);
        }
        return attemptRepo.findByReceiptIdOrderByAttemptNumberAsc(receiptId).stream()
                .map(ReceiptAttemptResponseDto::from)
                .toList();
    }

    /**
     * Ends a reading: the final fields become what it found, and the receipt COMPLETED_RAW when it holds a usable total or
     * FAILED, with the reason, when it holds none. Returns the attempt that records the reading, with the answer exactly
     * as it arrived, for the caller to save.
     */
    private static ReceiptProcessingAttempt recordReading(Receipt receipt, ExtractedReceiptDto extracted, int attemptNumber,
                                                          String aiRawData, String clientId) {
        receipt.setVendorName(extracted.getSeller());
        receipt.setExtractedTotal(extracted.getTotalCost());
        receipt.setExtractedDate(extracted.getTimestamp() != null ? extracted.getTimestamp().toLocalDate() : null);
        LocalDateTime endedAt = LocalDateTime.now();
        if (extracted.getTotalCost() != null) {
            receipt.moveTo(ReceiptStatus.COMPLETED_RAW);
            return ReceiptProcessingAttempt.completed(receipt, attemptNumber, aiRawData, clientId, endedAt);
        }
        ReceiptErrorCode reason = readNothing(extracted) ? ReceiptErrorCode.AI_UNREADABLE : ReceiptErrorCode.AI_NO_TOTAL;
        receipt.failWith(reason);
        return ReceiptProcessingAttempt.failed(receipt, attemptNumber, aiRawData, clientId, reason, endedAt);
    }

    private static void addIfChanged(List<String> changedFields, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changedFields.add(field);
        }
    }

    /** True when the answer held none of the fields of a receipt (the total is known to be missing already). */
    private static boolean readNothing(ExtractedReceiptDto extracted) {
        return extracted.getSeller() == null && extracted.getAddress() == null && extracted.getTimestamp() == null;
    }

    /** True for a request that carries the key of a retry the receipt has recorded already; one without a key never is. */
    private boolean wasRecordedBefore(Long receiptId, String clientId) {
        return clientId != null && attemptRepo.existsByReceiptIdAndClientId(receiptId, clientId);
    }

    /**
     * The number of the receipt's latest reading. A receipt that has none yet but still holds the answer of a reading
     * made before attempts existed gets that answer recorded first, as a LEGACY attempt, so that the reading about to be
     * recorded comes after it and nothing of it is lost, whether or not the migration that imports such answers has run
     * yet. The migration applies the same rule ({@link #legacyAnswersOf}) and skips a receipt that has attempts already.
     */
    private int keepLegacyReadings(Receipt receipt) {
        int last = attemptRepo.findLastAttemptNumber(receipt.getId());
        if (last > 0) {
            return last;
        }
        LocalDateTime recordedAt = Objects.requireNonNullElseGet(receipt.getCreatedAt(), LocalDateTime::now);
        for (String answer : legacyAnswersOf(receipt)) {
            last++;
            attemptRepo.save(ReceiptProcessingAttempt.legacy(receipt, last, answer, recordedAt));
        }
        if (last > 0) {
            log.info("Legacy AI answers of the receipt kept as attempts before its retry, receiptId={}, attempts={}",
                    receipt.getId(), last);
        }
        return last;
    }

    /**
     * The answers of the AI a receipt held before attempts existed, oldest first and each once: the copy that the checkout
     * of the builds before the lifecycle wrote onto the transaction, then the one Receipt.aiRawData holds when it differs
     * from it (the build that introduced the lifecycle kept the answer there and overwrote it on every retry).
     */
    private static List<String> legacyAnswersOf(Receipt receipt) {
        List<String> answers = new ArrayList<>();
        Transaction carrier = receipt.getTransaction();
        if (carrier != null && carrier.getRawReceiptData() != null) {
            answers.add(carrier.getRawReceiptData());
        }
        if (receipt.getAiRawData() != null && !answers.contains(receipt.getAiRawData())) {
            answers.add(receipt.getAiRawData());
        }
        return answers;
    }

    /** An amount the owner sends: two decimals, above zero and plausible. Anything else is refused before the database is asked. */
    private static BigDecimal validAmount(BigDecimal amount) {
        BigDecimal rounded = amount == null ? null : amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (rounded == null || rounded.signum() <= 0 || rounded.compareTo(OcrService.MAX_TOTAL) > 0) {
            throw new IllegalArgumentException(MSG_AMOUNT_INVALID);
        }
        return rounded;
    }

    /** A date the owner sends is held to the rule OcrService applies to one the AI read; a field that was left out passes. */
    private static LocalDate validReceiptDate(LocalDate date) {
        if (date != null && (date.getYear() < OcrService.MIN_YEAR || date.getYear() > OcrService.MAX_YEAR)) {
            throw new IllegalArgumentException(MSG_DATE_INVALID);
        }
        return date;
    }

    /** A receipt that does not exist is a 404, one that exists but is somebody else's (or nobody's) a 403. */
    private Receipt findOwnedReceipt(Long receiptId, Long userId) {
        Receipt receipt = receiptRepo.findDetailedById(receiptId).orElseThrow(() -> {
            log.warn("Receipt not found, userId={}, receiptId={}", userId, receiptId);
            return new ReceiptNotFoundException(MSG_RECEIPT_NOT_FOUND);
        });
        requireOwner(receipt, userId);
        return receipt;
    }

    /** The owner of a receipt is the owner of the transaction that carries it; a receipt none carries has no owner. */
    private static void requireOwner(Receipt receipt, Long userId) {
        Transaction carrier = receipt.getTransaction();
        if (carrier == null || !carrier.getUser().getId().equals(userId)) {
            log.warn("Receipt access denied, userId={}, receiptId={}", userId, receipt.getId());
            throw new TransactionAccessDeniedException(MSG_RECEIPT_FORBIDDEN);
        }
    }
}
