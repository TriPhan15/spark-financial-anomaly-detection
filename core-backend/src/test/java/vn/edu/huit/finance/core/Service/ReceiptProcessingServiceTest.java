package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.ReceiptAttemptResponseDto;
import vn.edu.huit.finance.core.Dto.ReceiptResponseDto;
import vn.edu.huit.finance.core.Dto.ReceiptRetryRequestDto;
import vn.edu.huit.finance.core.Dto.ReceiptUpdateRequestDto;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.ReceiptErrorCode;
import vn.edu.huit.finance.core.Entity.ReceiptProcessingAttempt;
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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReceiptProcessingServiceTest {

    private static final Long ALICE_ID = 1L;
    private static final Long BOB_ID = 2L;
    private static final Long RECEIPT_ID = 7L;
    private static final Long TRANSACTION_ID = 100L;
    private static final Long VERSION = 3L;
    private static final LocalDateTime CLIENT_DATE = LocalDateTime.of(2026, 9, 20, 9, 0);
    private static final String CLIENT_LOCATION = "Hóa đơn quét";

    @Mock private ReceiptRepository receiptRepo;
    @Mock private ReceiptProcessingAttemptRepository attemptRepo;
    @Mock private CurrentUserProvider currentUserProvider;
    @Spy private OcrService ocrService = new OcrService();
    @Mock private AuditLogService auditLogService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private ReceiptProcessingService service;

    private final User alice = user(ALICE_ID);
    private final User bob = user(BOB_ID);

    // ---------------------------------------------------------------------------------------------------------- fixtures

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setEmail("user" + id + "@example.com");
        return user;
    }

    private static Transaction transactionOf(User owner) {
        Transaction transaction = new Transaction();
        transaction.setId(TRANSACTION_ID);
        transaction.setUser(owner);
        transaction.setAmount(new BigDecimal("200000"));
        transaction.setTransactionDate(CLIENT_DATE);
        transaction.setLocation(CLIENT_LOCATION);
        return transaction;
    }

    /** A saved receipt: it has an id and a version, and its readings are the business of the attempts repository. */
    private static Receipt receipt(String status, Transaction carrier) {
        Receipt receipt = new Receipt();
        receipt.setId(RECEIPT_ID);
        receipt.setVersion(VERSION);
        receipt.setStatus(status);
        receipt.setExtractedTotal(new BigDecimal("1.00"));
        receipt.setTransaction(carrier);
        return receipt;
    }

    private void signedInAsAlice() {
        when(currentUserProvider.getCurrentUser()).thenReturn(alice);
    }

    private void repositoryHolds(Receipt receipt) {
        when(receiptRepo.findDetailedById(RECEIPT_ID)).thenReturn(Optional.of(receipt));
    }

    private void savingAnswersWithTheReceipt() {
        when(receiptRepo.saveAndFlush(any(Receipt.class))).thenAnswer(returnsFirstArg());
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected + " but was null");
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    /** Every row the service recorded in the transaction of the change, in order. */
    private List<AuditEntry> recordedEntries() {
        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService, atLeastOnce()).record(entries.capture());
        return entries.getAllValues();
    }

    /** What the AI answered never becomes part of a row, nor anything the client wrote. */
    private static void assertNoneContains(List<AuditEntry> entries, String... texts) {
        for (AuditEntry entry : entries) {
            for (String text : texts) {
                assertFalse(entry.description().contains(text), entry.action() + " must not contain " + text);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------- feedback

    @Test
    void theOwnerOfAFailedReceiptSendsTheAmountAndTheReceiptWaitsForReview() {
        signedInAsAlice();
        Receipt failed = receipt("FAILED", transactionOf(alice));
        repositoryHolds(failed);

        service.submitFeedback(RECEIPT_ID, new BigDecimal("150000"));

        assertEquals("WAITING_FOR_REVIEW", failed.getStatus());
        assertMoney("150000", failed.getExtractedTotal());
        verify(receiptRepo).save(failed);
        verify(currentUserProvider).getCurrentUser();
    }

    @Test
    void theStatusIsMatchedIgnoringCaseBecauseOlderRowsWereWrittenByHand() {
        signedInAsAlice();
        Receipt failed = receipt("failed", transactionOf(alice));
        repositoryHolds(failed);

        service.submitFeedback(RECEIPT_ID, new BigDecimal("150000"));

        assertEquals("WAITING_FOR_REVIEW", failed.getStatus());
    }

    @Test
    void feedbackClearsTheReasonTheReceiptHadFailedForAndLeavesItsReadingsAlone() {
        signedInAsAlice();
        Receipt failed = receipt("FAILED", transactionOf(alice));
        failed.setErrorCode("AI_NO_TOTAL");
        repositoryHolds(failed);

        service.submitFeedback(RECEIPT_ID, new BigDecimal("150000"));

        assertNull(failed.getErrorCode(), "an error code only describes a FAILED receipt");
        verifyNoInteractions(attemptRepo);
    }

    @Test
    void aReceiptOfSomebodyElseIsRefusedAndNothingIsWritten() {
        signedInAsAlice();
        Receipt bobs = receipt("FAILED", transactionOf(bob));
        repositoryHolds(bobs);

        assertThrows(TransactionAccessDeniedException.class, () -> service.submitFeedback(RECEIPT_ID, new BigDecimal("150000")));

        assertEquals("FAILED", bobs.getStatus());
        assertMoney("1", bobs.getExtractedTotal());
        verify(receiptRepo, never()).save(any());
    }

    @Test
    void aReceiptNoTransactionCarriesHasNoOwnerAndIsRefusedForEverybody() {
        signedInAsAlice();
        Receipt orphan = receipt("FAILED", null);
        repositoryHolds(orphan);

        assertThrows(TransactionAccessDeniedException.class, () -> service.submitFeedback(RECEIPT_ID, new BigDecimal("150000")));

        assertEquals("FAILED", orphan.getStatus());
        verify(receiptRepo, never()).save(any());
    }

    @Test
    void ownershipIsCheckedBeforeTheStatusSoTheStateOfSomebodyElsesReceiptIsNotRevealed() {
        signedInAsAlice();
        repositoryHolds(receipt("SUCCESS", transactionOf(bob)));

        assertThrows(TransactionAccessDeniedException.class, () -> service.submitFeedback(RECEIPT_ID, new BigDecimal("150000")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "SUCCESS", "COMPLETED_RAW", "EDITED", "WAITING_FOR_REVIEW", "APPROVED", "CANCELED"})
    void onlyAFailedReceiptTakesFeedback(String status) {
        signedInAsAlice();
        Receipt receipt = receipt(status, transactionOf(alice));
        repositoryHolds(receipt);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.submitFeedback(RECEIPT_ID, new BigDecimal("150000")));

        assertTrue(refused.getMessage().contains("FAILED"));
        assertEquals(status, receipt.getStatus());
        verify(receiptRepo, never()).save(any());
    }

    @Test
    void aReceiptThatDoesNotExistIsNotFound() {
        signedInAsAlice();
        when(receiptRepo.findDetailedById(RECEIPT_ID)).thenReturn(Optional.empty());

        assertThrows(ReceiptNotFoundException.class, () -> service.submitFeedback(RECEIPT_ID, new BigDecimal("150000")));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-5", "0.004", "1000000000001"})
    void anAmountThatIsNotPositiveOrPlausibleIsRejectedBeforeAnythingIsLoaded(String amount) {
        BigDecimal manualAmount = amount == null ? null : new BigDecimal(amount);

        assertThrows(IllegalArgumentException.class, () -> service.submitFeedback(RECEIPT_ID, manualAmount));

        verifyNoInteractions(receiptRepo, currentUserProvider);
    }

    @Test
    void noPublicMethodTakesAUserIdBecauseTheCallerAlwaysComesFromTheSecurityContext() {
        Set<String> takingAUserId = Arrays.stream(ReceiptProcessingService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getParameters()).anyMatch(parameter -> parameter.getName().equals("userId")))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(), takingAUserId);
    }

    @Test
    void feedbackRunsInsideADatabaseTransaction() throws NoSuchMethodException {
        Method feedback = ReceiptProcessingService.class.getMethod("submitFeedback", Long.class, BigDecimal.class);

        assertTrue(AnnotatedElementUtils.hasAnnotation(feedback, Transactional.class));
    }

    @Test
    void feedbackReportsAStatusChangeOfTheOwnersReceiptForTheStatisticsCache() {
        signedInAsAlice();
        repositoryHolds(receipt("FAILED", transactionOf(alice)));

        service.submitFeedback(RECEIPT_ID, new BigDecimal("150000"));

        verify(eventPublisher).publishEvent(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_STATUS, ALICE_ID));
    }

    @Test
    void feedbackIsRecordedWithTheOwnerAndTheAmountTheAdministratorWillApprove() {
        signedInAsAlice();
        repositoryHolds(receipt("FAILED", transactionOf(alice)));

        service.submitFeedback(RECEIPT_ID, new BigDecimal("150000"));

        AuditEntry entry = recordedEntries().get(0);
        assertEquals(AuditAction.RECEIPT_FEEDBACK_SUBMITTED, entry.action());
        assertEquals(AuditActor.of(alice), entry.actor());
        assertEquals(RECEIPT_ID, entry.targetId());
        assertEquals("Gửi số tiền đề nghị 150000.00 VNĐ để quản trị viên duyệt: FAILED → WAITING_FOR_REVIEW", entry.description());
    }

    @Test
    void refusedFeedbackReportsNothing() {
        signedInAsAlice();
        repositoryHolds(receipt("FAILED", transactionOf(bob)));

        assertThrows(TransactionAccessDeniedException.class, () -> service.submitFeedback(RECEIPT_ID, new BigDecimal("150000")));

        verifyNoInteractions(eventPublisher, auditLogService);
    }

    // ------------------------------------------------------------------------------------------ rejection (administrators)

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUCCESS", "COMPLETED_RAW", "EDITED", "WAITING_FOR_REVIEW", "APPROVED"})
    void anAdministratorRejectsAReceiptAndOnlyItsStatusChanges(String status) {
        Transaction transaction = transactionOf(alice);
        Receipt receipt = receipt(status, transaction);
        repositoryHolds(receipt);

        service.rejectReceipt(RECEIPT_ID);

        assertEquals("FAILED", receipt.getStatus());
        assertEquals("REJECTED_BY_ADMIN", receipt.getErrorCode(), "the receipt says that it was rejected, not misread");
        assertMoney("1.00", receipt.getExtractedTotal());
        verifyNoInteractions(attemptRepo);
        assertMoney("200000", transaction.getAmount());
        verify(receiptRepo).save(receipt);
        verifyNoInteractions(currentUserProvider);
    }

    @Test
    void rejectingAReceiptThatIsFailedAlreadyChangesNothingAndKeepsWhyItFailed() {
        Receipt failed = receipt("FAILED", transactionOf(alice));
        failed.setErrorCode("AI_NO_TOTAL");
        repositoryHolds(failed);

        service.rejectReceipt(RECEIPT_ID);

        assertEquals("FAILED", failed.getStatus());
        assertEquals("AI_NO_TOTAL", failed.getErrorCode());
        verify(receiptRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "CANCELED", "NOT_A_STATUS"})
    void aReceiptThatIsBeingReadOrWasWithdrawnCannotBeRejected(String status) {
        Receipt receipt = receipt(status, transactionOf(alice));
        repositoryHolds(receipt);

        assertThrows(IllegalStateException.class, () -> service.rejectReceipt(RECEIPT_ID));

        assertEquals(status, receipt.getStatus());
        verify(receiptRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aRejectionReportsAStatusChangeOfTheOwnersReceiptNotOfTheAdministrators() {
        repositoryHolds(receipt("WAITING_FOR_REVIEW", transactionOf(bob)));

        service.rejectReceipt(RECEIPT_ID);

        verify(eventPublisher).publishEvent(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_STATUS, BOB_ID));
    }

    @Test
    void aRejectionIsRecordedWithTheAdministratorWhoCalledAsActorAndTheOwnerOnlyNamed() {
        repositoryHolds(receipt("WAITING_FOR_REVIEW", transactionOf(bob)));

        service.rejectReceipt(RECEIPT_ID);

        AuditEntry entry = recordedEntries().get(0);
        assertEquals(AuditAction.RECEIPT_REJECTED, entry.action());
        // Never the owner as actor: the administrator is the caller of the request, looked up when the row is written.
        assertEquals(AuditActor.currentCaller(), entry.actor());
        assertEquals(RECEIPT_ID, entry.targetId());
        assertEquals("Từ chối hóa đơn: WAITING_FOR_REVIEW → FAILED (REJECTED_BY_ADMIN), chủ sở hữu #2", entry.description());
    }

    @Test
    void rejectingAReceiptThatIsFailedAlreadyRecordsNothing() {
        repositoryHolds(receipt("FAILED", transactionOf(alice)));

        service.rejectReceipt(RECEIPT_ID);

        verifyNoInteractions(auditLogService);
    }

    @Test
    void aReceiptNoTransactionCarriesIsRejectedTooAndOnlyTheAdminFiguresAreReported() {
        Receipt orphan = receipt("PENDING", null);
        repositoryHolds(orphan);

        service.rejectReceipt(RECEIPT_ID);

        assertEquals("FAILED", orphan.getStatus());
        verify(eventPublisher).publishEvent(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_STATUS));
    }

    @Test
    void rejectingAReceiptThatDoesNotExistIsNotFoundAndReportsNothing() {
        when(receiptRepo.findDetailedById(RECEIPT_ID)).thenReturn(Optional.empty());

        assertThrows(ReceiptNotFoundException.class, () -> service.rejectReceipt(RECEIPT_ID));

        verify(receiptRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aRejectionRunsInsideADatabaseTransaction() throws NoSuchMethodException {
        Method reject = ReceiptProcessingService.class.getMethod("rejectReceipt", Long.class);

        assertTrue(AnnotatedElementUtils.hasAnnotation(reject, Transactional.class));
    }

    // ---------------------------------------------------------------------------------------- linking a scanned receipt

    private static final String FULL_SCAN = "{\"seller\": {\"seller_name\": \"Bách Hóa Xanh\", \"address\": \"12 Nguyễn Trãi, Quận 1\","
            + " \"timestamp\": \"27/05/2026 10:30\", \"total_cost\": \"245.000\", \"total_cost_number\": 245000}}";
    private static final String UNREADABLE_TOTAL = "{\"seller\": {\"seller_name\": \"Highlands\", \"total_cost\": \"0\"}}";

    /** The one attempt a new receipt carries: the reading of the checkout, saved with the receipt by the cascade. */
    private static ReceiptProcessingAttempt firstAttemptOf(Transaction transaction) {
        List<ReceiptProcessingAttempt> attempts = transaction.getReceipt().getAttempts();
        assertEquals(1, attempts.size(), "a checkout records exactly one reading");
        ReceiptProcessingAttempt first = attempts.get(0);
        assertSame(transaction.getReceipt(), first.getReceipt());
        assertEquals(1, first.getAttemptNumber());
        assertNull(first.getClientId(), "the key of a checkout belongs to the transaction");
        return first;
    }

    @Test
    void aFullyReadReceiptIsLinkedBothWaysToItsTransactionAndHoldsWhatTheAiRead() {
        Transaction transaction = transactionOf(alice);

        service.attachReceipt(transaction, FULL_SCAN, "  https://img.example.com/1.jpg ");

        Receipt receipt = transaction.getReceipt();
        assertNotNull(receipt);
        assertSame(transaction, receipt.getTransaction());
        assertEquals("COMPLETED_RAW", receipt.getStatus(), "straight from the reading: never PENDING or PROCESSING");
        assertNull(receipt.getErrorCode());
        assertEquals("Bách Hóa Xanh", receipt.getVendorName());
        assertMoney("245000", receipt.getExtractedTotal());
        assertEquals(LocalDate.of(2026, 5, 27), receipt.getExtractedDate());
        assertEquals("https://img.example.com/1.jpg", receipt.getImageUrl());
        assertEquals(LocalDateTime.of(2026, 5, 27, 10, 30), transaction.getTransactionDate());
        assertEquals("12 Nguyễn Trãi, Quận 1", transaction.getLocation());
        ReceiptProcessingAttempt first = firstAttemptOf(transaction);
        assertEquals("COMPLETED", first.getStatus());
        assertNull(first.getErrorCode());
        assertNotNull(first.getCompletedAt());
        // Nothing is saved here: the transaction is saved by its caller and cascades to the receipt and the attempt.
        verifyNoInteractions(receiptRepo, attemptRepo);
    }

    @Test
    void aSavedReceiptOfACheckoutIsRecordedAsCreatedWithTheOutcomeOfItsFirstReadingAndNotWhatTheAiAnswered() {
        Transaction transaction = transactionOf(alice);
        service.attachReceipt(transaction, FULL_SCAN, null);
        transaction.getReceipt().setId(RECEIPT_ID);

        service.recordAttachedReceipt(transaction.getReceipt(), AuditActor.of(alice));

        List<AuditEntry> entries = recordedEntries();
        assertEquals(List.of(AuditAction.RECEIPT_CREATED, AuditAction.RECEIPT_PROCESSING_SUCCEEDED),
                entries.stream().map(AuditEntry::action).toList());
        entries.forEach(entry -> {
            assertEquals(AuditActor.of(alice), entry.actor());
            assertEquals(RECEIPT_ID, entry.targetId());
        });
        assertEquals("Hóa đơn mới của giao dịch #100", entries.get(0).description());
        assertNoneContains(entries, FULL_SCAN, "Bách Hóa Xanh", "Nguyễn Trãi", "245");
    }

    @Test
    void aCheckoutReadingWithoutTotalIsRecordedAsAFailedProcessingWithItsReason() {
        Transaction transaction = transactionOf(alice);
        service.attachReceipt(transaction, UNREADABLE_TOTAL, null);
        transaction.getReceipt().setId(RECEIPT_ID);

        service.recordAttachedReceipt(transaction.getReceipt(), AuditActor.system());

        AuditEntry outcome = recordedEntries().get(1);
        assertEquals(AuditAction.RECEIPT_PROCESSING_FAILED, outcome.action());
        assertEquals(AuditActor.system(), outcome.actor());
        assertEquals("Lần đọc #1: AI không đọc được tổng tiền (AI_NO_TOTAL), trạng thái FAILED", outcome.description());
    }

    @Test
    void theAnswerOfTheAiIsKeptInTheFirstAttemptExactlyAsItArrived() {
        Transaction transaction = transactionOf(alice);
        String spacedOut = "  " + FULL_SCAN + "\n";

        service.attachReceipt(transaction, spacedOut, null);

        assertEquals(spacedOut, firstAttemptOf(transaction).getAiRawData(), "not trimmed, not re-serialised");
        assertNull(transaction.getReceipt().getAiRawData(), "the legacy column of the receipt is not written any more");
    }

    @Test
    void whenTheAiReadNoTotalTheReceiptFailsAndKeepsAwaitingTheAmountOfItsOwner() {
        Transaction transaction = transactionOf(alice);

        service.attachReceipt(transaction, UNREADABLE_TOTAL, null);

        Receipt receipt = transaction.getReceipt();
        assertEquals("FAILED", receipt.getStatus());
        assertEquals("AI_NO_TOTAL", receipt.getErrorCode());
        assertNull(receipt.getExtractedTotal());
        assertEquals("Highlands", receipt.getVendorName());
        assertSame(transaction, receipt.getTransaction());
        ReceiptProcessingAttempt first = firstAttemptOf(transaction);
        assertEquals(UNREADABLE_TOTAL, first.getAiRawData(), "what the AI answered is kept for the failed reading too");
        assertEquals("FAILED", first.getStatus());
        assertEquals("AI_NO_TOTAL", first.getErrorCode());
        assertEquals(ReceiptErrorCode.AI_NO_TOTAL.getMessage(), first.getSafeErrorMessage());
    }

    @Test
    void whatTheAiDidNotDeliverKeepsTheValuesTheClientSent() {
        Transaction transaction = transactionOf(alice);

        service.attachReceipt(transaction, "{\"seller\": {\"seller_name\": \"Highlands\", \"total_cost_number\": 90000}}", null);

        Receipt receipt = transaction.getReceipt();
        assertEquals("COMPLETED_RAW", receipt.getStatus());
        assertNull(receipt.getExtractedDate());
        assertNull(receipt.getImageUrl());
        assertEquals(CLIENT_DATE, transaction.getTransactionDate());
        assertEquals(CLIENT_LOCATION, transaction.getLocation());
    }

    @Test
    void aTimestampThatIsNotARealDateKeepsTheDateOfTheClient() {
        Transaction transaction = transactionOf(alice);

        service.attachReceipt(transaction, "{\"seller\": {\"timestamp\": \"32/13/2026 10:30\", \"total_cost_number\": 90000}}", null);

        assertEquals(CLIENT_DATE, transaction.getTransactionDate());
        assertNull(transaction.getReceipt().getExtractedDate());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"raw_text\": \"noise\"}", "[]"})
    void dataTheAiCouldNotStructureStillKeepsTheImageAsAFailedReceiptOfTheTransaction(String raw) {
        Transaction transaction = transactionOf(alice);

        service.attachReceipt(transaction, raw, "https://img.example.com/2.jpg");

        Receipt receipt = transaction.getReceipt();
        assertEquals("FAILED", receipt.getStatus());
        assertEquals("AI_UNREADABLE", receipt.getErrorCode());
        assertEquals(raw, firstAttemptOf(transaction).getAiRawData());
        assertEquals("AI_UNREADABLE", firstAttemptOf(transaction).getErrorCode());
        assertEquals("https://img.example.com/2.jpg", receipt.getImageUrl());
        assertSame(transaction, receipt.getTransaction());
        assertEquals(CLIENT_DATE, transaction.getTransactionDate());
        assertEquals(CLIENT_LOCATION, transaction.getLocation());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void aBlankImageUrlIsNotStored(String imageUrl) {
        Transaction transaction = transactionOf(alice);

        service.attachReceipt(transaction, FULL_SCAN, imageUrl);

        assertNull(transaction.getReceipt().getImageUrl());
    }

    // ------------------------------------------------------------------------------------------------ reading a receipt

    @Test
    void theOwnerReadsAReceiptWithItsVersionAndWithoutTheAnswerOfTheAi() {
        signedInAsAlice();
        Receipt receipt = receipt("COMPLETED_RAW", transactionOf(alice));
        receipt.setVendorName("Bách Hóa Xanh");
        repositoryHolds(receipt);

        ReceiptResponseDto found = service.getMyReceipt(RECEIPT_ID);
        verifyNoInteractions(attemptRepo);

        assertEquals(RECEIPT_ID, found.getId());
        assertEquals(TRANSACTION_ID, found.getTransactionId());
        assertEquals("COMPLETED_RAW", found.getStatus());
        assertEquals(VERSION, found.getVersion());
        assertEquals("Bách Hóa Xanh", found.getVendorName());
        assertMoney("1.00", found.getExtractedTotal());
        assertFalse(found.toString().contains("seller_name"), "nothing of the AI's JSON is part of the answer");
    }

    @Test
    void aRowThatPredatesTheLifecycleIsReadUnderTheNameTheLifecycleGivesIt() {
        signedInAsAlice();
        repositoryHolds(receipt("success", transactionOf(alice)));

        assertEquals("COMPLETED_RAW", service.getMyReceipt(RECEIPT_ID).getStatus());
    }

    @Test
    void aFailedReceiptIsReadWithTheFixedTextOfItsReason() {
        signedInAsAlice();
        Receipt failed = receipt("FAILED", transactionOf(alice));
        failed.setErrorCode("AI_NO_TOTAL");
        repositoryHolds(failed);

        ReceiptResponseDto found = service.getMyReceipt(RECEIPT_ID);

        assertEquals("AI_NO_TOTAL", found.getErrorCode());
        assertEquals("AI không đọc được tổng tiền trên hóa đơn", found.getErrorMessage());
    }

    @Test
    void nobodyReadsAReceiptOfSomebodyElseOrOneThatBelongsToNobody() {
        signedInAsAlice();
        when(receiptRepo.findDetailedById(RECEIPT_ID))
                .thenReturn(Optional.of(receipt("COMPLETED_RAW", transactionOf(bob))))
                .thenReturn(Optional.of(receipt("COMPLETED_RAW", null)))
                .thenReturn(Optional.empty());

        assertThrows(TransactionAccessDeniedException.class, () -> service.getMyReceipt(RECEIPT_ID));
        assertThrows(TransactionAccessDeniedException.class, () -> service.getMyReceipt(RECEIPT_ID));
        assertThrows(ReceiptNotFoundException.class, () -> service.getMyReceipt(RECEIPT_ID));
    }

    @Test
    void theReceiptOfATransactionIsFoundThroughTheTransactionForItsOwnerOnly() {
        signedInAsAlice();
        when(receiptRepo.findDetailedByTransactionId(TRANSACTION_ID))
                .thenReturn(Optional.of(receipt("EDITED", transactionOf(alice))))
                .thenReturn(Optional.of(receipt("EDITED", transactionOf(bob))))
                .thenReturn(Optional.empty());

        assertEquals(RECEIPT_ID, service.getMyReceiptOfTransaction(TRANSACTION_ID).getId());
        assertThrows(TransactionAccessDeniedException.class, () -> service.getMyReceiptOfTransaction(TRANSACTION_ID));
        assertThrows(ReceiptNotFoundException.class, () -> service.getMyReceiptOfTransaction(TRANSACTION_ID));
    }

    // ------------------------------------------------------------------------------------------------- editing a receipt

    private static ReceiptUpdateRequestDto edit(Long version) {
        ReceiptUpdateRequestDto request = new ReceiptUpdateRequestDto();
        request.setVersion(version);
        return request;
    }

    private static ReceiptUpdateRequestDto fullEdit(Long version) {
        ReceiptUpdateRequestDto request = edit(version);
        request.setVendorName("  Bách Hóa Xanh Q1 ");
        request.setExtractedTotal(new BigDecimal("250000.005"));
        request.setExtractedDate(LocalDate.of(2026, 5, 28));
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED_RAW", "EDITED", "SUCCESS", "success"})
    void theOwnerCorrectsAReceiptThatWasReadAndItBecomesEdited(String status) {
        signedInAsAlice();
        Transaction transaction = transactionOf(alice);
        Receipt receipt = receipt(status, transaction);
        repositoryHolds(receipt);
        savingAnswersWithTheReceipt();

        ReceiptResponseDto edited = service.editMyReceipt(RECEIPT_ID, fullEdit(VERSION));

        assertEquals("EDITED", receipt.getStatus());
        assertEquals("Bách Hóa Xanh Q1", receipt.getVendorName());
        assertMoney("250000.01", receipt.getExtractedTotal());
        assertEquals(LocalDate.of(2026, 5, 28), receipt.getExtractedDate());
        assertEquals("EDITED", edited.getStatus());
        assertEquals("Bách Hóa Xanh Q1", edited.getVendorName());
        verify(receiptRepo).saveAndFlush(receipt);
        // What the edit must leave alone: the readings of the AI, and the transaction with the wallet behind it.
        verifyNoInteractions(attemptRepo);
        assertMoney("200000", transaction.getAmount());
        assertEquals(CLIENT_DATE, transaction.getTransactionDate());
    }

    @Test
    void onlyTheFieldsTheRequestCarriesChange() {
        signedInAsAlice();
        Receipt receipt = receipt("COMPLETED_RAW", transactionOf(alice));
        receipt.setVendorName("Highlands");
        receipt.setExtractedDate(LocalDate.of(2026, 5, 27));
        repositoryHolds(receipt);
        savingAnswersWithTheReceipt();
        ReceiptUpdateRequestDto request = edit(VERSION);
        request.setExtractedTotal(new BigDecimal("90000"));

        service.editMyReceipt(RECEIPT_ID, request);

        assertMoney("90000", receipt.getExtractedTotal());
        assertEquals("Highlands", receipt.getVendorName());
        assertEquals(LocalDate.of(2026, 5, 27), receipt.getExtractedDate());
    }

    @Test
    void aBlankVendorNameClearsTheVendor() {
        signedInAsAlice();
        Receipt receipt = receipt("EDITED", transactionOf(alice));
        receipt.setVendorName("Highlands");
        repositoryHolds(receipt);
        savingAnswersWithTheReceipt();
        ReceiptUpdateRequestDto request = edit(VERSION);
        request.setVendorName("   ");

        service.editMyReceipt(RECEIPT_ID, request);

        assertNull(receipt.getVendorName());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {2L, 4L})
    void anEditMadeFromAnotherVersionThanTheCurrentOneIsRefusedAndNothingIsWritten(Long sentVersion) {
        signedInAsAlice();
        Receipt receipt = receipt("COMPLETED_RAW", transactionOf(alice));
        repositoryHolds(receipt);

        assertThrows(ReceiptConflictException.class, () -> service.editMyReceipt(RECEIPT_ID, fullEdit(sentVersion)));

        assertEquals("COMPLETED_RAW", receipt.getStatus());
        assertMoney("1.00", receipt.getExtractedTotal());
        verify(receiptRepo, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher, auditLogService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "FAILED", "WAITING_FOR_REVIEW", "APPROVED", "CANCELED", "NOT_A_STATUS"})
    void aReceiptThatWasNotReadOrIsSettledAlreadyCannotBeEdited(String status) {
        signedInAsAlice();
        Receipt receipt = receipt(status, transactionOf(alice));
        repositoryHolds(receipt);

        assertThrows(ReceiptConflictException.class, () -> service.editMyReceipt(RECEIPT_ID, fullEdit(VERSION)));

        assertEquals(status, receipt.getStatus());
        assertMoney("1.00", receipt.getExtractedTotal());
        assertNull(receipt.getVendorName());
        verify(receiptRepo, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher, auditLogService);
    }

    @Test
    void nobodyEditsAReceiptOfSomebodyElseWhateverItsStateAndTheVersionTheySend() {
        signedInAsAlice();
        Receipt bobs = receipt("COMPLETED_RAW", transactionOf(bob));
        repositoryHolds(bobs);

        assertThrows(TransactionAccessDeniedException.class, () -> service.editMyReceipt(RECEIPT_ID, fullEdit(VERSION)));
        // Ownership comes first: neither a wrong version nor a wrong state turns the answer into something else.
        assertThrows(TransactionAccessDeniedException.class, () -> service.editMyReceipt(RECEIPT_ID, fullEdit(99L)));

        assertEquals("COMPLETED_RAW", bobs.getStatus());
        assertNull(bobs.getVendorName());
        verify(receiptRepo, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher, auditLogService);
    }

    @Test
    void aReceiptThatBelongsToNobodyIsEditedByNobodyAndOneThatDoesNotExistIsNotFound() {
        signedInAsAlice();
        when(receiptRepo.findDetailedById(RECEIPT_ID))
                .thenReturn(Optional.of(receipt("COMPLETED_RAW", null)))
                .thenReturn(Optional.empty());

        assertThrows(TransactionAccessDeniedException.class, () -> service.editMyReceipt(RECEIPT_ID, fullEdit(VERSION)));
        assertThrows(ReceiptNotFoundException.class, () -> service.editMyReceipt(RECEIPT_ID, fullEdit(VERSION)));

        verify(receiptRepo, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.004", "1000000000001"})
    void aTotalThatIsNotPositiveOrPlausibleIsRejectedBeforeAnythingIsLoaded(String total) {
        ReceiptUpdateRequestDto request = edit(VERSION);
        request.setExtractedTotal(new BigDecimal(total));

        assertThrows(IllegalArgumentException.class, () -> service.editMyReceipt(RECEIPT_ID, request));

        verifyNoInteractions(receiptRepo, currentUserProvider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1999-12-31", "2101-01-01"})
    void aDateNoReceiptCanHaveIsRejectedBeforeAnythingIsLoaded(String date) {
        ReceiptUpdateRequestDto request = edit(VERSION);
        request.setExtractedDate(LocalDate.parse(date));

        assertThrows(IllegalArgumentException.class, () -> service.editMyReceipt(RECEIPT_ID, request));

        verifyNoInteractions(receiptRepo, currentUserProvider);
    }

    @Test
    void anEditIsAuditedAndReportsWhatTheReceiptSaysForTheStatisticsCache() {
        signedInAsAlice();
        repositoryHolds(receipt("COMPLETED_RAW", transactionOf(alice)));
        savingAnswersWithTheReceipt();

        service.editMyReceipt(RECEIPT_ID, fullEdit(VERSION));

        AuditEntry entry = recordedEntries().get(0);
        assertEquals(AuditAction.RECEIPT_UPDATED, entry.action());
        assertEquals(AuditActor.of(alice), entry.actor());
        assertEquals(RECEIPT_ID, entry.targetId());
        assertEquals("Sửa hóa đơn (tên cửa hàng, tổng tiền, ngày trên hóa đơn): COMPLETED_RAW → EDITED, phiên bản 3",
                entry.description());
        verify(eventPublisher).publishEvent(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_CONTENT, ALICE_ID));
        verifyNoMoreInteractions(eventPublisher);
    }

    @Test
    void anEditRunsInsideADatabaseTransaction() throws NoSuchMethodException {
        Method edit = ReceiptProcessingService.class.getMethod("editMyReceipt", Long.class, ReceiptUpdateRequestDto.class);

        assertTrue(AnnotatedElementUtils.hasAnnotation(edit, Transactional.class));
    }

    // ------------------------------------------------------------------------------------------------ retrying a receipt

    private static final String NEW_SCAN = "{\"seller\": {\"seller_name\": \"Highlands Coffee\", \"timestamp\": \"2026-09-20 08:15\","
            + " \"total_cost_number\": 95000}}";
    private static final String CLIENT_ID = "retry-attempt-1";
    private static final String LEGACY_ANSWER = "{\"seller\": {\"seller_name\": \"Highlands\"}}";
    private static final String PHASE_ONE_ANSWER = "{\"raw_text\": \"what a retry of the first lifecycle build overwrote\"}";
    private static final LocalDateTime RECEIPT_CREATED_AT = LocalDateTime.of(2026, 6, 1, 8, 0);

    private static ReceiptRetryRequestDto retry(String rawReceiptData, String clientId) {
        ReceiptRetryRequestDto request = new ReceiptRetryRequestDto();
        request.setRawReceiptData(rawReceiptData);
        request.setClientId(clientId);
        return request;
    }

    /** Alice's receipt as a failed reading left it. */
    private static Receipt failedReceipt(String status, Transaction carrier) {
        Receipt receipt = receipt(status, carrier);
        receipt.setErrorCode("AI_NO_TOTAL");
        receipt.setExtractedTotal(null);
        return receipt;
    }

    /**
     * Alice retries her FAILED receipt, which has {@code lastAttempt} readings recorded: the repository answers with the
     * receipt first as it is, then as the claim left it (still FAILED, one version further). Returns that second copy,
     * the one the new reading is recorded on.
     */
    private Receipt aliceRetries(String failedStatus, int lastAttempt) {
        signedInAsAlice();
        Transaction transaction = transactionOf(alice);
        Receipt claimed = failedReceipt("FAILED", transaction);
        claimed.setVersion(VERSION + 1);
        when(receiptRepo.findDetailedById(RECEIPT_ID))
                .thenReturn(Optional.of(failedReceipt(failedStatus, transaction)))
                .thenReturn(Optional.of(claimed));
        when(receiptRepo.claimFailedForRetry(RECEIPT_ID, VERSION)).thenReturn(1);
        when(attemptRepo.findLastAttemptNumber(RECEIPT_ID)).thenReturn(lastAttempt);
        savingAnswersWithTheReceipt();
        return claimed;
    }

    /** Every attempt the service saved, in the order it saved them. */
    private List<ReceiptProcessingAttempt> savedAttempts() {
        ArgumentCaptor<ReceiptProcessingAttempt> saved = ArgumentCaptor.forClass(ReceiptProcessingAttempt.class);
        verify(attemptRepo, atLeastOnce()).save(saved.capture());
        return saved.getAllValues();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "failed"})
    void aFailedReceiptIsClaimedAndANewReadingWithATotalCompletesItAsItsNextAttempt(String failedStatus) {
        Receipt current = aliceRetries(failedStatus, 1);

        ReceiptResponseDto retried = service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, null));

        verify(receiptRepo).claimFailedForRetry(RECEIPT_ID, VERSION);
        assertEquals("COMPLETED_RAW", current.getStatus());
        assertNull(current.getErrorCode());
        assertEquals("Highlands Coffee", current.getVendorName());
        assertMoney("95000", current.getExtractedTotal());
        assertEquals(LocalDate.of(2026, 9, 20), current.getExtractedDate());
        verify(receiptRepo).saveAndFlush(current);
        assertEquals("COMPLETED_RAW", retried.getStatus());
        assertEquals(RECEIPT_ID, retried.getId());
        // One new attempt, after the reading the receipt had; the earlier attempt is not even loaded.
        List<ReceiptProcessingAttempt> attempts = savedAttempts();
        assertEquals(1, attempts.size());
        ReceiptProcessingAttempt attempt = attempts.get(0);
        assertSame(current, attempt.getReceipt());
        assertEquals(2, attempt.getAttemptNumber());
        assertEquals("COMPLETED", attempt.getStatus());
        assertEquals(NEW_SCAN, attempt.getAiRawData());
        assertNull(attempt.getClientId());
        verify(attemptRepo, never()).findByReceiptIdOrderByAttemptNumberAsc(any());
        // A retry is about the receipt alone: the transaction keeps its amount, its date and its place.
        assertMoney("200000", current.getTransaction().getAmount());
        assertEquals(CLIENT_DATE, current.getTransaction().getTransactionDate());
        assertEquals(CLIENT_LOCATION, current.getTransaction().getLocation());
    }

    @Test
    void aNewReadingThatStillHoldsNoTotalLeavesTheReceiptFailedAndIsRecordedAsAFailedAttempt() {
        String stillUnreadable = "{\"raw_text\": \"noise\"}";
        Receipt current = aliceRetries("FAILED", 1);

        ReceiptResponseDto retried = service.retryMyReceipt(RECEIPT_ID, retry(stillUnreadable, null));

        assertEquals("FAILED", current.getStatus());
        assertEquals("AI_UNREADABLE", current.getErrorCode(), "the reason of the new reading replaces the old one");
        assertNull(current.getExtractedTotal());
        assertEquals("FAILED", retried.getStatus());
        assertEquals("AI không đọc được nội dung hóa đơn", retried.getErrorMessage());
        verify(receiptRepo).saveAndFlush(current);
        List<ReceiptProcessingAttempt> attempts = savedAttempts();
        assertEquals(1, attempts.size());
        assertEquals(2, attempts.get(0).getAttemptNumber());
        assertEquals("FAILED", attempts.get(0).getStatus());
        assertEquals("AI_UNREADABLE", attempts.get(0).getErrorCode());
        assertEquals(stillUnreadable, attempts.get(0).getAiRawData());
        List<AuditEntry> entries = recordedEntries();
        assertEquals(List.of(AuditAction.RECEIPT_RETRIED, AuditAction.RECEIPT_PROCESSING_FAILED),
                entries.stream().map(AuditEntry::action).toList());
        assertEquals("Lần đọc #2: AI không đọc được tổng tiền (AI_UNREADABLE), trạng thái FAILED", entries.get(1).description());
        assertNoneContains(entries, stillUnreadable, "noise");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "COMPLETED_RAW", "SUCCESS", "EDITED", "WAITING_FOR_REVIEW", "APPROVED", "CANCELED",
            "NOT_A_STATUS"})
    void onlyAFailedReceiptIsRetried(String status) {
        signedInAsAlice();
        Receipt receipt = receipt(status, transactionOf(alice));
        repositoryHolds(receipt);

        assertThrows(ReceiptConflictException.class, () -> service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, CLIENT_ID)));

        assertEquals(status, receipt.getStatus());
        verify(receiptRepo, never()).claimFailedForRetry(any(), any());
        verify(receiptRepo, never()).saveAndFlush(any());
        verify(attemptRepo, never()).save(any());
        verifyNoInteractions(ocrService, eventPublisher, auditLogService);
    }

    @Test
    void aRetryThatLostTheClaimToAnotherRequestIsRefusedAndRecordsNothing() {
        signedInAsAlice();
        Transaction transaction = transactionOf(alice);
        Receipt asTheOtherRequestLeftIt = receipt("COMPLETED_RAW", transaction);
        when(receiptRepo.findDetailedById(RECEIPT_ID))
                .thenReturn(Optional.of(failedReceipt("FAILED", transaction)))
                .thenReturn(Optional.of(asTheOtherRequestLeftIt));
        when(receiptRepo.claimFailedForRetry(RECEIPT_ID, VERSION)).thenReturn(0);

        assertThrows(ReceiptConflictException.class, () -> service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, CLIENT_ID)));

        assertEquals("COMPLETED_RAW", asTheOtherRequestLeftIt.getStatus());
        // Asked before the claim and again after it: the other request did not carry this key.
        verify(attemptRepo, times(2)).existsByReceiptIdAndClientId(RECEIPT_ID, CLIENT_ID);
        verify(attemptRepo, never()).save(any());
        verify(receiptRepo, never()).saveAndFlush(any());
        verifyNoInteractions(ocrService, eventPublisher, auditLogService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED_RAW", "FAILED", "EDITED"})
    void aRepeatOfARetryTheReceiptRecordedIsAnsweredWithTheReceiptAsItIsNow(String statusSinceThen) {
        signedInAsAlice();
        Receipt receipt = receipt(statusSinceThen, transactionOf(alice));
        repositoryHolds(receipt);
        when(attemptRepo.existsByReceiptIdAndClientId(RECEIPT_ID, CLIENT_ID)).thenReturn(true);

        ReceiptResponseDto repeated = service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, "  " + CLIENT_ID + " "));

        assertEquals(statusSinceThen, repeated.getStatus());
        assertEquals(VERSION, repeated.getVersion());
        // Nothing is claimed, read or written a second time.
        verify(receiptRepo, never()).claimFailedForRetry(any(), any());
        verify(receiptRepo, never()).saveAndFlush(any());
        verify(attemptRepo, never()).save(any());
        verifyNoInteractions(ocrService, eventPublisher, auditLogService);
    }

    @Test
    void aRepeatThatArrivesTogetherWithTheFirstRequestIsAnsweredWithItsResult() {
        signedInAsAlice();
        Transaction transaction = transactionOf(alice);
        Receipt asTheFirstRequestLeftIt = receipt("COMPLETED_RAW", transaction);
        when(receiptRepo.findDetailedById(RECEIPT_ID))
                .thenReturn(Optional.of(failedReceipt("FAILED", transaction)))
                .thenReturn(Optional.of(asTheFirstRequestLeftIt));
        // Not recorded yet when the repeat arrives; recorded by the first request once the claim has waited for it.
        when(attemptRepo.existsByReceiptIdAndClientId(RECEIPT_ID, CLIENT_ID)).thenReturn(false).thenReturn(true);
        when(receiptRepo.claimFailedForRetry(RECEIPT_ID, VERSION)).thenReturn(0);

        ReceiptResponseDto repeated = service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, CLIENT_ID));

        assertEquals("COMPLETED_RAW", repeated.getStatus());
        verify(attemptRepo, never()).save(any());
        verify(receiptRepo, never()).saveAndFlush(any());
        verifyNoInteractions(ocrService, eventPublisher, auditLogService);
    }

    @Test
    void aRetryWithoutAKeyIsNeverTakenForARepeat() {
        signedInAsAlice();
        Receipt receipt = receipt("COMPLETED_RAW", transactionOf(alice));
        repositoryHolds(receipt);

        assertThrows(ReceiptConflictException.class, () -> service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, null)));
        assertThrows(ReceiptConflictException.class, () -> service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, "   ")));

        verify(attemptRepo, never()).existsByReceiptIdAndClientId(any(), any());
    }

    @Test
    void theKeyOfTheRetryIsKeptTrimmedWithItsAttempt() {
        aliceRetries("FAILED", 1);

        service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, " " + CLIENT_ID + "  "));

        verify(attemptRepo, times(1)).existsByReceiptIdAndClientId(RECEIPT_ID, CLIENT_ID);
        assertEquals(CLIENT_ID, savedAttempts().get(0).getClientId());
    }

    @Test
    void aFailureWhileTheRetryIsWrittenReachesTheCallerSoTheWholeTransactionRollsBack() {
        signedInAsAlice();
        Transaction transaction = transactionOf(alice);
        when(receiptRepo.findDetailedById(RECEIPT_ID))
                .thenReturn(Optional.of(failedReceipt("FAILED", transaction)))
                .thenReturn(Optional.of(failedReceipt("FAILED", transaction)));
        when(receiptRepo.claimFailedForRetry(RECEIPT_ID, VERSION)).thenReturn(1);
        when(receiptRepo.saveAndFlush(any(Receipt.class))).thenThrow(new IllegalStateException("database went away"));

        assertThrows(IllegalStateException.class, () -> service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, CLIENT_ID)));

        verifyNoInteractions(eventPublisher, auditLogService);
    }

    // ----------------------------------------------------------------- retrying a receipt that predates the attempts

    @Test
    void theLegacyAnswersOfAReceiptWithoutAttemptsAreRecordedFirstOldestFirstAndTheRetryAfterThem() {
        Receipt current = aliceRetries("FAILED", 0);
        current.getTransaction().setRawReceiptData(LEGACY_ANSWER);
        ReflectionTestUtils.setField(current, "aiRawData", PHASE_ONE_ANSWER);
        current.setCreatedAt(RECEIPT_CREATED_AT);

        service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, CLIENT_ID));

        List<ReceiptProcessingAttempt> attempts = savedAttempts();
        assertEquals(List.of(1, 2, 3), attempts.stream().map(ReceiptProcessingAttempt::getAttemptNumber).toList());
        assertEquals(List.of("LEGACY", "LEGACY", "COMPLETED"), attempts.stream().map(ReceiptProcessingAttempt::getStatus).toList());
        assertEquals(List.of(LEGACY_ANSWER, PHASE_ONE_ANSWER, NEW_SCAN),
                attempts.stream().map(ReceiptProcessingAttempt::getAiRawData).toList());
        assertEquals(RECEIPT_CREATED_AT, attempts.get(0).getCreatedAt(), "an imported reading is dated by its receipt");
        assertNull(attempts.get(0).getCompletedAt());
        assertNull(attempts.get(0).getClientId());
        assertEquals(CLIENT_ID, attempts.get(2).getClientId());
        assertEquals("COMPLETED_RAW", current.getStatus());
    }

    @Test
    void aLegacyAnswerThatBothLegacyColumnsHoldIsRecordedOnce() {
        Receipt current = aliceRetries("FAILED", 0);
        current.getTransaction().setRawReceiptData(LEGACY_ANSWER);
        ReflectionTestUtils.setField(current, "aiRawData", LEGACY_ANSWER);

        service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, null));

        assertEquals(List.of(LEGACY_ANSWER, NEW_SCAN), savedAttempts().stream().map(ReceiptProcessingAttempt::getAiRawData).toList());
    }

    @Test
    void aReceiptWithoutAttemptsOrLegacyAnswersRecordsTheRetryAsItsFirstReading() {
        aliceRetries("FAILED", 0);

        service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, null));

        List<ReceiptProcessingAttempt> attempts = savedAttempts();
        assertEquals(1, attempts.size());
        assertEquals(1, attempts.get(0).getAttemptNumber());
        assertEquals("COMPLETED", attempts.get(0).getStatus());
    }

    @Test
    void aReceiptThatHasAttemptsImportsNoLegacyAnswerAgain() {
        Receipt current = aliceRetries("FAILED", 3);
        current.getTransaction().setRawReceiptData(LEGACY_ANSWER);

        service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, null));

        List<ReceiptProcessingAttempt> attempts = savedAttempts();
        assertEquals(1, attempts.size());
        assertEquals(4, attempts.get(0).getAttemptNumber());
    }

    // ------------------------------------------------------------------------------------------ retrying: who and how

    @Test
    void nobodyRetriesAReceiptOfSomebodyElseOrOneThatBelongsToNobody() {
        signedInAsAlice();
        when(receiptRepo.findDetailedById(RECEIPT_ID))
                .thenReturn(Optional.of(receipt("FAILED", transactionOf(bob))))
                .thenReturn(Optional.of(receipt("FAILED", null)))
                .thenReturn(Optional.empty());

        assertThrows(TransactionAccessDeniedException.class, () -> service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, CLIENT_ID)));
        assertThrows(TransactionAccessDeniedException.class, () -> service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, CLIENT_ID)));
        assertThrows(ReceiptNotFoundException.class, () -> service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, CLIENT_ID)));

        verify(receiptRepo, never()).claimFailedForRetry(any(), any());
        verify(receiptRepo, never()).saveAndFlush(any());
        // Ownership comes first: not even whether somebody else's receipt knows the key is revealed.
        verifyNoInteractions(attemptRepo, ocrService, eventPublisher, auditLogService);
    }

    @Test
    void aRetryIsAuditedWithTheNumberOfItsReadingAndReportsWhatTheReceiptSaysForTheStatisticsCache() {
        aliceRetries("FAILED", 1);

        service.retryMyReceipt(RECEIPT_ID, retry(NEW_SCAN, null));

        List<AuditEntry> entries = recordedEntries();
        assertEquals(List.of(AuditAction.RECEIPT_RETRIED, AuditAction.RECEIPT_PROCESSING_SUCCEEDED),
                entries.stream().map(AuditEntry::action).toList());
        entries.forEach(entry -> {
            assertEquals(AuditActor.of(alice), entry.actor());
            assertEquals(RECEIPT_ID, entry.targetId());
        });
        assertEquals("Đọc lại hóa đơn (lần đọc #2)", entries.get(0).description());
        assertEquals("Lần đọc #2: AI đọc được tổng tiền, trạng thái COMPLETED_RAW", entries.get(1).description());
        assertNoneContains(entries, NEW_SCAN, "Highlands Coffee");
        verify(eventPublisher).publishEvent(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_CONTENT, ALICE_ID));
        verifyNoMoreInteractions(eventPublisher);
    }

    @Test
    void aRetryRunsInsideADatabaseTransaction() throws NoSuchMethodException {
        Method retry = ReceiptProcessingService.class.getMethod("retryMyReceipt", Long.class, ReceiptRetryRequestDto.class);

        assertTrue(AnnotatedElementUtils.hasAnnotation(retry, Transactional.class));
    }

    // --------------------------------------------------------------------------- the readings, for administrators only

    @Test
    void anAdministratorSeesEveryReadingOfAReceiptOldestFirstWithWhatTheAiAnswered() {
        Receipt receipt = receipt("COMPLETED_RAW", transactionOf(alice));
        when(receiptRepo.existsById(RECEIPT_ID)).thenReturn(true);
        when(attemptRepo.findByReceiptIdOrderByAttemptNumberAsc(RECEIPT_ID)).thenReturn(List.of(
                ReceiptProcessingAttempt.failed(receipt, 1, UNREADABLE_TOTAL, null, ReceiptErrorCode.AI_NO_TOTAL, CLIENT_DATE),
                ReceiptProcessingAttempt.completed(receipt, 2, NEW_SCAN, CLIENT_ID, CLIENT_DATE.plusDays(1))));

        List<ReceiptAttemptResponseDto> attempts = service.getAttemptsOf(RECEIPT_ID);

        assertEquals(List.of(1, 2), attempts.stream().map(ReceiptAttemptResponseDto::getAttemptNumber).toList());
        assertEquals(List.of(RECEIPT_ID, RECEIPT_ID), attempts.stream().map(ReceiptAttemptResponseDto::getReceiptId).toList());
        assertEquals("FAILED", attempts.get(0).getStatus());
        assertEquals("AI_NO_TOTAL", attempts.get(0).getErrorCode());
        assertEquals(ReceiptErrorCode.AI_NO_TOTAL.getMessage(), attempts.get(0).getSafeErrorMessage());
        assertEquals(UNREADABLE_TOTAL, attempts.get(0).getAiRawData());
        assertEquals("COMPLETED", attempts.get(1).getStatus());
        assertEquals(NEW_SCAN, attempts.get(1).getAiRawData());
        assertEquals(CLIENT_ID, attempts.get(1).getClientId());
        assertEquals(CLIENT_DATE.plusDays(1), attempts.get(1).getCompletedAt());
        verifyNoInteractions(currentUserProvider, eventPublisher, auditLogService);
    }

    @Test
    void theReadingsOfAReceiptThatDoesNotExistAreNotFound() {
        when(receiptRepo.existsById(RECEIPT_ID)).thenReturn(false);

        assertThrows(ReceiptNotFoundException.class, () -> service.getAttemptsOf(RECEIPT_ID));

        verifyNoInteractions(attemptRepo);
    }

    @Test
    void theReadingsAreReadInAReadOnlyTransaction() throws NoSuchMethodException {
        Method read = ReceiptProcessingService.class.getMethod("getAttemptsOf", Long.class);

        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(read, Transactional.class);
        assertNotNull(transactional);
        assertTrue(transactional.readOnly());
    }
}
