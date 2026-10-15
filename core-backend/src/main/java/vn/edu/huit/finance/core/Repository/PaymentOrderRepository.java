package vn.edu.huit.finance.core.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.huit.finance.core.Entity.PaymentOrder;

import java.util.List;
import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByOrderCode(String orderCode);

    List<PaymentOrder> findByUserIdOrderByCreatedAtDesc(Long userId);
}
