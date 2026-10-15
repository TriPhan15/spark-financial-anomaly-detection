package vn.edu.huit.finance.core.Service.Payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.edu.huit.finance.core.Util.HmacUtil;

@Component
public class VnpayGateway extends AbstractMockBackedGateway {

    @Value("${payment.vnpay.secret}")
    private String secret;

    @Override
    public String name() {
        return "VNPAY";
    }

    @Override
    protected String sign(String raw) {
        return HmacUtil.hmacSha512(raw, secret);
    }
}
