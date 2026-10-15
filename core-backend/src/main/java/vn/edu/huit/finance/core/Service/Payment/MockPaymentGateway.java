package vn.edu.huit.finance.core.Service.Payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.edu.huit.finance.core.Util.HmacUtil;

@Component
public class MockPaymentGateway extends AbstractMockBackedGateway {

    @Value("${payment.secret}")
    private String secret;

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    protected String sign(String raw) {
        return HmacUtil.hmacSha256(raw, secret);
    }
}
