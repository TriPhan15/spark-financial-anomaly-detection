package vn.edu.huit.finance.core.Service.Payment;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaymentGatewayRegistry {

    private final Map<String, PaymentGateway> byName;

    public PaymentGatewayRegistry(List<PaymentGateway> gateways) {
        this.byName = gateways.stream()
                .collect(Collectors.toMap(g -> g.name().toUpperCase(), Function.identity()));
    }

    public PaymentGateway get(String provider) {
        PaymentGateway g = byName.get(provider == null ? null : provider.toUpperCase());
        if (g == null) {
            throw new RuntimeException("Cong thanh toan khong ho tro: " + provider
                    + " (cho phep: " + String.join(", ", byName.keySet()) + ")");
        }
        return g;
    }

    public boolean supports(String provider) {
        return provider != null && byName.containsKey(provider.toUpperCase());
    }
}
