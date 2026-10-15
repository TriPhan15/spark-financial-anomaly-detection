package vn.edu.huit.finance.core.Dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.ReceiptStatus;
import vn.edu.huit.finance.core.Entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TransactionResponseDtoTest {

    private static Transaction transaction(String note) {
        Transaction transaction = new Transaction();
        transaction.setId(100L);
        transaction.setAmount(new BigDecimal("150000"));
        transaction.setExpense(true);
        transaction.setCategoryName("Ăn uống");
        transaction.setNote(note);
        transaction.setTransactionDate(LocalDateTime.of(2026, 9, 1, 12, 0));
        return transaction;
    }

    @Test
    void everyFieldOfTheTransactionIsCarriedOver() {
        TransactionResponseDto dto = TransactionResponseDto.from(transaction("Bún bò"));

        assertEquals(100L, dto.getId());
        assertEquals(new BigDecimal("150000"), dto.getAmount());
        assertTrue(dto.isExpense());
        assertEquals("Ăn uống", dto.getCategory());
        assertEquals(LocalDateTime.of(2026, 9, 1, 12, 0), dto.getDate());
    }

    @Test
    void anIncomeStaysAnIncome() {
        Transaction income = transaction("Lương");
        income.setExpense(false);

        assertFalse(TransactionResponseDto.from(income).isExpense());
    }

    @Test
    void theTitleIsTheNoteWhenThereIsOne() {
        assertEquals("Bún bò", TransactionResponseDto.from(transaction("Bún bò")).getTitle());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t"})
    void withoutARealNoteTheTitleIsTheNameOfTheCategory(String note) {
        assertEquals("Ăn uống", TransactionResponseDto.from(transaction(note)).getTitle());
    }

    @Test
    void theImageComesFromTheReceiptAndIsMissingWithoutOne() {
        Transaction withReceipt = transaction("Bún bò");
        Receipt receipt = new Receipt();
        receipt.setImageUrl("https://img.example.com/1.jpg");
        withReceipt.setReceipt(receipt);

        assertEquals("https://img.example.com/1.jpg", TransactionResponseDto.from(withReceipt).getImageUrl());
        assertNull(TransactionResponseDto.from(transaction("Bún bò")).getImageUrl());
    }

    private static String receiptStatusOfATransactionWhoseReceiptIs(String storedStatus) {
        Transaction withReceipt = transaction("Bún bò");
        Receipt receipt = new Receipt();
        receipt.setStatus(storedStatus);
        withReceipt.setReceipt(receipt);
        return TransactionResponseDto.from(withReceipt).getReceiptStatus();
    }

    @ParameterizedTest
    @EnumSource(value = ReceiptStatus.class, names = {"PENDING", "PROCESSING", "FAILED", "WAITING_FOR_REVIEW", "CANCELED"})
    void theReceiptStatusIsTheNameOfTheStatusOfTheReceipt(ReceiptStatus status) {
        assertEquals(status.name(), receiptStatusOfATransactionWhoseReceiptIs(status.name()));
    }

    // The mobile app that is in use knows SUCCESS for a receipt that was read, and shows no badge for a name it does not know.
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED_RAW", "EDITED", "APPROVED", "SUCCESS", "success"})
    void aReceiptThatWasReadIsSuccessForTheAppWhateverBecameOfItSince(String storedStatus) {
        assertEquals("SUCCESS", receiptStatusOfATransactionWhoseReceiptIs(storedStatus));
    }

    @Test
    void everyStatusOfTheLifecycleReachesTheAppUnderANameItKnows() {
        Set<String> knownToTheApp = Set.of("PENDING", "PROCESSING", "SUCCESS", "FAILED", "WAITING_FOR_REVIEW", "CANCELED");

        for (ReceiptStatus status : ReceiptStatus.values()) {
            assertTrue(knownToTheApp.contains(receiptStatusOfATransactionWhoseReceiptIs(status.name())), status.name());
        }
    }

    @Test
    void aStatusTheLifecycleDoesNotKnowIsPassedOnAsItIsStored() {
        assertEquals("Đang chờ", receiptStatusOfATransactionWhoseReceiptIs("Đang chờ"));
    }

    @Test
    void withoutAReceiptThereIsNoReceiptStatus() {
        assertNull(TransactionResponseDto.from(transaction("Bún bò")).getReceiptStatus());
    }
}
