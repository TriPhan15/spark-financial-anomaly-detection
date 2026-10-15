package vn.edu.huit.finance.core.Dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentWebhookDto {
    private String orderCode;
    private String gatewayTxnId;
    private String resultCode;
    private BigDecimal amount;
    private String signature;
}
