package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.huit.finance.core.Dto.ExtractedReceiptDto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** What is read from the AI's JSON: the four core fields, normalised, and a defined answer for everything that is off. */
class OcrServiceTest {

    private final OcrService ocr = new OcrService();

    /** {"seller": {...}}, the way the AI writes it. */
    private static String seller(String fields) {
        return "{\"seller\": {" + fields + "}}";
    }

    private static String field(String key, String value) {
        return "\"" + key + "\": \"" + value + "\"";
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected + " but nothing was read");
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    // ---------------------------------------------------------------------------------------------------- the good path

    @Test
    void readsTheFourCoreFieldsAndNormalisesThem() {
        String raw = seller(field("seller_name", "  Bách   Hóa\\tXanh ")
                + "," + field("address", "12 Nguyễn Trãi,\\n Quận 1")
                + "," + field("timestamp", "27/05/2026 10:30")
                + "," + field("total_cost", "245.000")
                + ", \"total_cost_number\": 245000");

        ExtractedReceiptDto result = ocr.extract(raw);

        assertEquals("Bách Hóa Xanh", result.getSeller());
        assertEquals("12 Nguyễn Trãi, Quận 1", result.getAddress());
        assertEquals(LocalDateTime.of(2026, 5, 27, 10, 30), result.getTimestamp());
        assertMoney("245000", result.getTotalCost());
    }

    @Test
    void theFieldsMayComeWithoutTheSellerWrapperAndSomeMayBeMissing() {
        ExtractedReceiptDto result = ocr.extract("{\"seller_name\": \"Highlands\", \"total_cost_number\": 90000}");

        assertEquals("Highlands", result.getSeller());
        assertMoney("90000", result.getTotalCost());
        assertNull(result.getAddress());
        assertNull(result.getTimestamp());
    }

    // ------------------------------------------------------------------------------------ nothing usable: never throws

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not json", "{broken", "[1, 2]", "null", "\"text\"", "{\"raw_text\": \"noise\"}",
            "{\"seller\": {}}"})
    void inputTheAiCouldNotStructureGivesAnEmptyResultInsteadOfAnException(String raw) {
        assertEquals(ExtractedReceiptDto.empty(), ocr.extract(raw));
    }

    // -------------------------------------------------------------------------------------------------------- timestamp

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2026-05-27 10:30      | 2026-05-27T10:30:00",
            "2026/05/27 10:30      | 2026-05-27T10:30:00",
            "2026.05.27T10:30:45   | 2026-05-27T10:30:45",
            "27/05/2026 10:30      | 2026-05-27T10:30:00",
            "27-05-2026 08.05      | 2026-05-27T08:05:00",
            "10:30 27/05/2026      | 2026-05-27T10:30:00",
            "5/9/2026 7:05         | 2026-09-05T07:05:00",
            "27.05.2026            | 2026-05-27T00:00:00",
            "27/05/2026 25:99      | 2026-05-27T00:00:00"})
    void aTimestampInAnyCommonLayoutBecomesADateTime(String printed, String expected) {
        assertEquals(LocalDateTime.parse(expected), ocr.extract(seller(field("timestamp", printed))).getTimestamp());
    }

    @ParameterizedTest
    @ValueSource(strings = {"32/13/2026 10:30", "31/02/2026", "27/05/1999 10:30", "27/05/2101", "hôm qua", "10:30", "  "})
    void aTimestampThatIsNotARealDateIsMissingInsteadOfWrong(String printed) {
        assertNull(ocr.extract(seller(field("timestamp", printed))).getTimestamp());
    }

    // -------------------------------------------------------------------------------------------------------- total cost

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "245.000                       | 245000",
            "245.000 VND                   | 245000",
            "1.250.000đ                    | 1250000",
            "1,250,000                     | 1250000",
            "1.250.000,50                  | 1250000.50",
            "1,250,000.50                  | 1250000.50",
            "12.50                         | 12.50",
            "245000                        | 245000",
            "Tổng cộng: 245.000 (VAT 10%)  | 245000",
            "0                             | NULL",
            "abc                           | NULL",
            "99999999999999999             | NULL"})
    void aPrintedTotalIsReadWithVietnameseOrEnglishSeparators(String printed, String expected) {
        BigDecimal total = ocr.extract(seller(field("total_cost", printed))).getTotalCost();

        if (expected == null) {
            assertNull(total);
        } else {
            assertMoney(expected, total);
        }
    }

    @Test
    void theNumberTheAiComputedWinsOverThePrintedText() {
        String raw = seller(field("total_cost", "245.000") + ", \"total_cost_number\": 100000");

        assertMoney("100000", ocr.extract(raw).getTotalCost());
    }

    @Test
    void aNumberGivenAsTextIsParsedToo() {
        assertMoney("245000", ocr.extract(seller("\"total_cost_number\": \"245000\"")).getTotalCost());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "null", "\"\"", "\"abc\""})
    void anUnusableNumberFallsBackToThePrintedText(String number) {
        String raw = seller(field("total_cost", "245.000") + ", \"total_cost_number\": " + number);

        assertMoney("245000", ocr.extract(raw).getTotalCost());
    }

    // ------------------------------------------------------------------------------------------------ seller and address

    @Test
    void overlongTextIsCutToTheLengthOfTheColumn() {
        String noise = "A".repeat(OcrService.MAX_TEXT_LENGTH + 45);

        assertEquals(OcrService.MAX_TEXT_LENGTH, ocr.extract(seller(field("seller_name", noise))).getSeller().length());
        assertEquals(OcrService.MAX_TEXT_LENGTH, ocr.extract(seller(field("address", noise))).getAddress().length());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "null", "NULL"})
    void emptyTextAndTheNullLiteralAreMissing(String printed) {
        assertNull(ocr.extract(seller(field("seller_name", printed))).getSeller());
        assertNull(ocr.extract(seller(field("address", printed))).getAddress());
    }

    @Test
    void aValueThatIsNotTextIsMissing() {
        ExtractedReceiptDto result = ocr.extract(seller("\"seller_name\": {\"a\": 1}, \"address\": [\"x\"]"));

        assertNull(result.getSeller());
        assertNull(result.getAddress());
    }
}
