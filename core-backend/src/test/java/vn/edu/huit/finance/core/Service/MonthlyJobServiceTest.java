package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The monthly scan of low wallets: one row by the system per account below the threshold, without its email or balance. */
@ExtendWith(MockitoExtension.class)
class MonthlyJobServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private MonthlyJobService service;

    private static User lowOn(long id, String balance) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@example.com");
        user.setWalletBalance(new BigDecimal(balance));
        return user;
    }

    @Test
    void everyAccountBelowTheThresholdGetsOneRowBySystem_aboutItself() {
        when(userRepository.findByWalletBalanceLessThan(TransactionService.LOW_BALANCE_THRESHOLD))
                .thenReturn(List.of(lowOn(3L, "12000"), lowOn(4L, "49999.99")));

        service.checkMonthlyBudgets();

        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService, times(2)).record(entries.capture());
        assertEquals(List.of(3L, 4L), entries.getAllValues().stream().map(AuditEntry::targetId).toList());
        for (AuditEntry entry : entries.getAllValues()) {
            assertEquals(AuditAction.SYSTEM_LOW_BALANCE_WARNING, entry.action());
            assertEquals(AuditActor.system(), entry.actor());
            assertEquals("Số dư ví dưới ngưỡng 50000 VNĐ khi quét đầu tháng", entry.description());
            assertFalse(entry.description().contains("@example.com"));
            assertFalse(entry.description().contains("12000"));
        }
    }

    @Test
    void noAccountBelowTheThresholdMeansNoRow() {
        when(userRepository.findByWalletBalanceLessThan(TransactionService.LOW_BALANCE_THRESHOLD)).thenReturn(List.of());

        service.checkMonthlyBudgets();

        verifyNoInteractions(auditLogService);
    }
}
