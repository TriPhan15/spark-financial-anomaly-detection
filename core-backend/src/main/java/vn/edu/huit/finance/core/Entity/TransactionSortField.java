package vn.edu.huit.finance.core.Entity;

import lombok.Getter;
import vn.edu.huit.finance.core.Exception.InvalidSortFieldException;

import java.util.Arrays;

/** The fields a client may sort transactions by; the value it sends is never passed to the query as it is. */
@Getter
public enum TransactionSortField {
    TRANSACTION_DATE("transactionDate"),
    AMOUNT("amount");

    private static final String MSG_UNSUPPORTED = "Trường sắp xếp không hợp lệ";

    private final String property;

    TransactionSortField(String property) {
        this.property = property;
    }

    /** Matches the name of the entity property, ignoring case ("amount", "transactionDate"). */
    public static TransactionSortField fromProperty(String property) {
        return Arrays.stream(values())
                .filter(field -> field.property.equalsIgnoreCase(property))
                .findFirst()
                .orElseThrow(() -> new InvalidSortFieldException(MSG_UNSUPPORTED));
    }
}
