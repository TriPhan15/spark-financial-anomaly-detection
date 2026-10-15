package vn.edu.huit.finance.core.Service.Payment;

import vn.edu.huit.finance.core.Entity.PaymentOrder;

public interface PaymentGateway {

    String name();

    String buildPaymentUrl(PaymentOrder order);

    boolean verifyRequestSignature(String orderCode, String amount, String sign);
}
