package vn.edu.huit.finance.core.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.util.List;

@Slf4j
@Service
public class MonthlyJobService {

    // The account is the target of the row; neither its email nor its balance is copied into the text.
    private static final String AUDIT_LOW_BALANCE = "Số dư ví dưới ngưỡng %s VNĐ khi quét đầu tháng";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogService auditLogService;

    /**
     * On the first day of each month, one SYSTEM_LOW_BALANCE_WARNING row, by the system, for every account whose wallet
     * is below the low-balance threshold; all rows in one transaction.
     */
    @Scheduled(cron = "0 0 0 1 * ?")
    @Transactional
    public void checkMonthlyBudgets() {
        log.info("Monthly wallet scan started");
        List<User> usersAtRisk = userRepository.findByWalletBalanceLessThan(TransactionService.LOW_BALANCE_THRESHOLD);
        String description = String.format(AUDIT_LOW_BALANCE, TransactionService.LOW_BALANCE_THRESHOLD.toPlainString());
        for (User user : usersAtRisk) {
            auditLogService.record(AuditEntry.of(AuditAction.SYSTEM_LOW_BALANCE_WARNING, AuditActor.system(), user.getId(),
                    description));
        }
        log.info("Monthly wallet scan finished, accountsBelowThreshold={}", usersAtRisk.size());
    }
}
