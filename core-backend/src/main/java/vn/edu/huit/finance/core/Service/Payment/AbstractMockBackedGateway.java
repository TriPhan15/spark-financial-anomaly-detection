package vn.edu.huit.finance.core.Service.Payment;

import org.springframework.beans.factory.annotation.Value;
import vn.edu.huit.finance.core.Entity.PaymentOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public abstract class AbstractMockBackedGateway implements PaymentGateway {

    @Value("${payment.mock-gateway-url}")
    protected String gatewayUrl;

    protected abstract String sign(String raw);

    @Override
    public String buildPaymentUrl(PaymentOrder order) {
        String amount = order.getAmount().toPlainString();
        String sign = sign(order.getOrderCode() + "|" + amount);
        return gatewayUrl + "/pay"
                + "?orderCode=" + enc(order.getOrderCode())
                + "&amount=" + enc(amount)
                + "&provider=" + enc(name())
                + "&sign=" + enc(sign);
    }

    @Override
    public boolean verifyRequestSignature(String orderCode, String amount, String sign) {
        if (sign == null) return false;
        String expected = this.sign(orderCode + "|" + amount);
        if (expected.length() != sign.length()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) diff |= expected.charAt(i) ^ sign.charAt(i);
        return diff == 0;
    }

    protected String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
