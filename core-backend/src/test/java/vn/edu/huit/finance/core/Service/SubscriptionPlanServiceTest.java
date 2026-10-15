package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.SubscriptionPlan;
import vn.edu.huit.finance.core.Repository.SubscriptionPlanRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Membership plans as administrators change them: each change with its row; a new plan is always a new row. */
@ExtendWith(MockitoExtension.class)
class SubscriptionPlanServiceTest {

    @Mock private SubscriptionPlanRepository repository;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private SubscriptionPlanService service;

    private static SubscriptionPlan values(String name) {
        SubscriptionPlan values = new SubscriptionPlan();
        values.setPlanName(name);
        values.setPrice(99000.0);
        values.setDurationDays(30);
        values.setMaxAiScans(100);
        values.setWcmSpadeEnabled(true);
        return values;
    }

    private List<AuditEntry> recorded() {
        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService, atLeastOnce()).record(entries.capture());
        return entries.getAllValues();
    }

    @Test
    void aNewPlanIsANewActiveRow_whateverIdOrStatusCameWithIt_andIsRecorded() {
        SubscriptionPlan sent = values("VIP");
        sent.setId(3L);
        sent.setActive(false);
        when(repository.save(any(SubscriptionPlan.class))).thenAnswer(invocation -> {
            SubscriptionPlan saved = invocation.getArgument(0);
            saved.setId(8L);
            return saved;
        });

        SubscriptionPlan created = service.createPlan(sent);

        assertNotSame(sent, created, "the client's object is never what gets saved");
        assertTrue(created.isActive());
        assertEquals("VIP", created.getPlanName());
        assertTrue(created.isWcmSpadeEnabled());
        AuditEntry entry = recorded().get(0);
        assertEquals(AuditAction.PLAN_CREATED, entry.action());
        assertEquals(AuditActor.currentCaller(), entry.actor());
        assertEquals(8L, entry.targetId());
        assertEquals("Tạo gói hội viên \"VIP\"", entry.description());
    }

    @Test
    void anUpdateAndBothTogglesAreRecorded() {
        SubscriptionPlan plan = values("VIP");
        plan.setId(8L);
        plan.setActive(true);
        when(repository.findById(8L)).thenReturn(Optional.of(plan));
        when(repository.save(any(SubscriptionPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updatePlan(8L, values("VIP Plus"));
        service.togglePlan(8L);
        service.togglePlan(8L);

        List<AuditEntry> entries = recorded();
        assertEquals(List.of(AuditAction.PLAN_UPDATED, AuditAction.PLAN_STATUS_CHANGED, AuditAction.PLAN_STATUS_CHANGED),
                entries.stream().map(AuditEntry::action).toList());
        assertEquals("Cập nhật thông tin gói \"VIP Plus\"", entries.get(0).description());
        assertEquals("Tạm ẩn gói \"VIP Plus\"", entries.get(1).description());
        assertEquals("Mở bán gói \"VIP Plus\"", entries.get(2).description());
    }

    @Test
    void aDeletionIsRecorded() {
        SubscriptionPlan plan = values("VIP");
        plan.setId(8L);
        when(repository.findById(8L)).thenReturn(Optional.of(plan));

        service.deletePlan(8L);

        verify(repository).delete(plan);
        assertEquals(AuditAction.PLAN_DELETED, recorded().get(0).action());
    }

    @Test
    void aPlanThatDoesNotExistIsRefused_andNothingIsRecorded() {
        when(repository.findById(9L)).thenReturn(Optional.empty());

        NoSuchElementException refused = assertThrows(NoSuchElementException.class, () -> service.togglePlan(9L));

        assertEquals("Không tìm thấy gói hội viên", refused.getMessage());
        verifyNoInteractions(auditLogService);
    }
}
