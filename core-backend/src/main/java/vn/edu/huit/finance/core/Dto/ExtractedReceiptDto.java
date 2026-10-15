package vn.edu.huit.finance.core.Dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The four core fields read from a receipt by the AI, already normalised. A field the AI did not deliver, or delivered
 * in a shape that cannot be trusted, is null: the caller decides which fallback applies.
 */
@Value
@Builder
public class ExtractedReceiptDto {
    /** Store name. */
    String seller;
    /** Street address of the store. */
    String address;
    /** When the purchase was made. */
    LocalDateTime timestamp;
    /** Total to pay; always positive when present. */
    BigDecimal totalCost;

    public static ExtractedReceiptDto empty() {
        return ExtractedReceiptDto.builder().build();
    }
}
