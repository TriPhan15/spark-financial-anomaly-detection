package vn.edu.huit.finance.core.Dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.Transaction;

/**
 * Body of POST /api/receipts/{id}/retry. The AI runs on the client, so a retry delivers what it answered for the new scan
 * of a receipt that is FAILED; core-backend never calls the AI itself. The owner is always the caller.
 */
@Getter
@Setter
public class ReceiptRetryRequestDto {

    /** What ai-service answered (POST /donut/scan), as one JSON text: the same value a checkout sends as rawReceiptData. */
    @NotBlank(message = "Thiếu dữ liệu AI của lần quét lại (rawReceiptData)")
    @Size(max = Receipt.AI_RAW_DATA_MAX_LENGTH, message = "Dữ liệu AI của hóa đơn (rawReceiptData) tối đa {max} ký tự")
    private String rawReceiptData;

    /**
     * Optional idempotency key, made up by the client for one attempt (a UUID will do) and sent again unchanged when that
     * attempt is repeated, like the clientId of a checkout. It is kept with the reading it recorded, so a repeat of any
     * earlier retry of the receipt is answered with the receipt as it is now, and nothing is read or written a second
     * time; the body of a repeat is not compared with the first one. Blank counts as absent: a retry without a key is
     * recorded whenever the receipt is FAILED, and refused once it is not.
     */
    @Size(max = Transaction.CLIENT_ID_MAX_LENGTH, message = "Mã định danh yêu cầu (clientId) tối đa {max} ký tự")
    private String clientId;
}
