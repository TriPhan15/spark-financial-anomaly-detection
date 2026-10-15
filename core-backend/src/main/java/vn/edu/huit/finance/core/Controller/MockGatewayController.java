package vn.edu.huit.finance.core.Controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import vn.edu.huit.finance.core.Dto.PaymentWebhookDto;
import vn.edu.huit.finance.core.Service.Payment.PaymentGatewayRegistry;
import vn.edu.huit.finance.core.Util.HmacUtil;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/mock-gateway")
public class MockGatewayController {

    @Value("${payment.secret}")
    private String secret;

    @Value("${payment.webhook-url}")
    private String webhookUrl;

    private final PaymentGatewayRegistry gateways;
    private final RestTemplate http = new RestTemplate();

    public MockGatewayController(PaymentGatewayRegistry gateways) {
        this.gateways = gateways;
    }

    @GetMapping(value = "/pay", produces = MediaType.TEXT_HTML_VALUE)
    public String payPage(@RequestParam String orderCode,
                          @RequestParam String amount,
                          @RequestParam(defaultValue = "MOCK") String provider,
                          @RequestParam String sign) {
        boolean ok;
        try {
            ok = gateways.get(provider).verifyRequestSignature(orderCode, amount, sign);
        } catch (Exception e) {
            return "<h2 style='color:red'>" + e.getMessage() + "</h2>";
        }
        if (!ok) {
            return "<h2 style='color:red'>Chu ky khong hop le</h2>";
        }
        String base = "/mock-gateway/confirm?orderCode=" + orderCode + "&amount=" + amount;
        return "<html><head><meta charset='utf-8'><title>Mock Gateway</title></head>"
                + "<body style='font-family:sans-serif;text-align:center;padding-top:60px'>"
                + "<h2>Cong thanh toan GIA LAP - " + provider + "</h2>"
                + "<p>Ma don: <b>" + orderCode + "</b></p>"
                + "<p>So tien: <b>" + amount + " d</b></p>"
                + "<a href='" + base + "&result=success'"
                + " style='display:inline-block;margin:10px;padding:14px 28px;background:#2e7d32;color:#fff;text-decoration:none;border-radius:8px'>Thanh toan THANH CONG</a>"
                + "<a href='" + base + "&result=fail'"
                + " style='display:inline-block;margin:10px;padding:14px 28px;background:#c62828;color:#fff;text-decoration:none;border-radius:8px'>Thanh toan THAT BAI</a>"
                + "</body></html>";
    }

    @GetMapping(value = "/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public String confirm(@RequestParam String orderCode,
                          @RequestParam String amount,
                          @RequestParam String result) {
        String resultCode = "success".equalsIgnoreCase(result) ? "00" : "99";
        String gatewayTxnId = "MOCK" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        PaymentWebhookDto dto = new PaymentWebhookDto();
        dto.setOrderCode(orderCode);
        dto.setGatewayTxnId(gatewayTxnId);
        dto.setResultCode(resultCode);
        dto.setAmount(new BigDecimal(amount));

        String raw = orderCode + "|" + gatewayTxnId + "|" + resultCode + "|" + dto.getAmount().toPlainString();
        dto.setSignature(HmacUtil.hmacSha256(raw, secret));

        try {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            http.postForEntity(webhookUrl, new HttpEntity<>(dto, h), String.class);
        } catch (Exception e) {
            return "<h2 style='color:red'>Goi webhook loi: " + e.getMessage() + "</h2>";
        }

        String color = "00".equals(resultCode) ? "#2e7d32" : "#c62828";
        String text = "00".equals(resultCode) ? "THANH CONG" : "THAT BAI";
        return "<html><head><meta charset='utf-8'></head>"
                + "<body style='font-family:sans-serif;text-align:center;padding-top:80px'>"
                + "<h1 style='color:" + color + "'>Thanh toan " + text + "</h1>"
                + "<p>Ma giao dich: " + gatewayTxnId + "</p>"
                + "<p>Quay lai ung dung de xem ket qua.</p>"
                + "</body></html>";
    }
}
