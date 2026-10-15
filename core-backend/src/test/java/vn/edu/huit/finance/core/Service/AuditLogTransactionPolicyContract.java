package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vn.edu.huit.finance.core.Entity.ActionLog;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.AuditLogWriteException;
import vn.edu.huit.finance.core.Repository.ActionLogRepository;
import vn.edu.huit.finance.core.Repository.CategoryRepository;
import vn.edu.huit.finance.core.Security.ClientIp;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditListener;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * The two failure policies of the activity log, against a real database, through the real transaction proxies:
 * <ul>
 *   <li>a change and its row (AuditLogService.record) commit together or not at all: a change that rolls back takes its
 *       row with it, and a row the database refuses takes the change with it;</li>
 *   <li>a row written on its own (a SecurityAuditEvent) stays when the request's transaction rolls back, and a row that
 *       cannot be written leaves the request as it was.</li>
 * </ul>
 * The database refuses a row for real, with a CHECK constraint no row can meet: the error, its translation and the
 * rollback are the database's, not a mock's. The subclasses name the database: H2 like the rest of the suite, and
 * PostgreSQL 15, where an error aborts the whole transaction it happens in.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AuditLogService.class, SecurityAuditListener.class, AuditLogTransactionPolicyContract.Changes.class})
abstract class AuditLogTransactionPolicyContract {

    private static final String REFUSE_EVERY_ROW = "alter table action_logs add constraint audit_policy_refuses check (id < 0)";
    private static final String ACCEPT_ROWS_AGAIN = "alter table action_logs drop constraint audit_policy_refuses";
    private static final String CLIENT = "203.0.113.7";

    /** A service with a change of its own (a category: a row with no relation), recorded the ways the services record. */
    static class Changes {
        @Autowired private CategoryRepository categoryRepository;
        @Autowired private AuditLogService auditLogService;
        @Autowired private ApplicationEventPublisher eventPublisher;

        @Transactional
        public Long createAndRecord(String name) {
            Category category = new Category();
            category.setName(name);
            category.setType("EXPENSE");
            Long id = categoryRepository.save(category).getId();
            auditLogService.record(AuditEntry.of(AuditAction.CATEGORY_CREATED, AuditActor.system(), id, "Thêm danh mục"));
            return id;
        }

        /** The change and its row are written, then the request fails: neither may remain. */
        @Transactional
        public void createRecordThenFail(String name) {
            createAndRecord(name);
            throw new IllegalStateException("failed after the row was written");
        }

        /** A request that is refused after it changed something: the change goes, the refusal stays. */
        @Transactional
        public void changeThenRefuse(String name) {
            Category category = new Category();
            category.setName(name);
            category.setType("EXPENSE");
            categoryRepository.save(category);
            eventPublisher.publishEvent(SecurityAuditEvent.ofCurrentCaller(AuditAction.AUTH_ACCESS_DENIED, "refused"));
            throw new IllegalStateException("refused");
        }

        /** A request that publishes a row of its own and goes on, whether or not the row could be written. */
        @Transactional
        public Long changeAndPublish(String name) {
            Category category = new Category();
            category.setName(name);
            category.setType("EXPENSE");
            Long id = categoryRepository.save(category).getId();
            eventPublisher.publishEvent(SecurityAuditEvent.ofCurrentCaller(AuditAction.AUTH_ACCESS_DENIED, "published"));
            return id;
        }
    }

    @Autowired private Changes changes;
    @Autowired private AuditLogService auditLogService;
    @Autowired private ActionLogRepository actionLogRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private CurrentUserProvider currentUserProvider;

    private boolean rowsRefused;

    @AfterEach
    void cleanUp() {
        if (rowsRefused) {
            jdbc.execute(ACCEPT_ROWS_AGAIN);
        }
        RequestContextHolder.resetRequestAttributes();
        actionLogRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private void theDatabaseRefusesEveryRow() {
        jdbc.execute(REFUSE_EVERY_ROW);
        rowsRefused = true;
    }

    private void aliceIsTheCaller() {
        User alice = new User();
        alice.setId(41L);
        alice.setEmail("alice@example.com");
        when(currentUserProvider.getCurrentUser()).thenReturn(alice);
    }

    private boolean categoryNamed(String name) {
        return categoryRepository.findByName(name).isPresent();
    }

    // ---------------------------------------------------------------------------------------- a change and its row

    @Test
    void aRowOfAChangeIsRefusedOutsideATransaction_soNoCallerCanForgetToMakeItAtomic() {
        assertThrows(IllegalTransactionStateException.class, () -> auditLogService.record(
                AuditEntry.of(AuditAction.CATEGORY_CREATED, AuditActor.system(), 1L, "outside")));

        assertEquals(0, actionLogRepository.count());
    }

    @Test
    void aChangeAndItsRowCommitTogether() {
        Long id = changes.createAndRecord("Ăn uống");

        List<ActionLog> rows = actionLogRepository.findAll();
        assertEquals(1, rows.size());
        assertEquals("CATEGORY_CREATED", rows.get(0).getAction());
        assertEquals("CATEGORY", rows.get(0).getTargetType());
        assertEquals(id, rows.get(0).getTargetId());
        assertEquals("SUCCESS", rows.get(0).getResult());
        assertTrue(categoryNamed("Ăn uống"));
    }

    @Test
    void aChangeThatRollsBackAfterItsRowWasWrittenLeavesNoRowClaimingIt() {
        assertThrows(IllegalStateException.class, () -> changes.createRecordThenFail("Mua sắm"));

        assertFalse(categoryNamed("Mua sắm"));
        assertEquals(0, actionLogRepository.count(), "no success row for a change that did not happen");
    }

    @Test
    void aRowTheDatabaseRefusesRollsTheChangeBack_andReachesTheCallerWithAFixedText() {
        theDatabaseRefusesEveryRow();

        AuditLogWriteException failed = assertThrows(AuditLogWriteException.class, () -> changes.createAndRecord("Du lịch"));

        assertFalse(categoryNamed("Du lịch"), "no change without its row");
        assertFalse(failed.getMessage().contains("action_logs"), "the database's own words stay on the server");
        assertNotNull(failed.getCause());
    }

    @Test
    void aRowNamesTheClientOfTheRequestItWasWrittenIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        request.addHeader(ClientIp.FORWARDED_FOR_HEADER, CLIENT + ", 10.0.0.2");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        changes.createAndRecord("Sức khỏe");

        assertEquals(CLIENT, actionLogRepository.findAll().get(0).getIpAddress());
    }

    // -------------------------------------------------------------------------------------------- rows of their own

    @Test
    void aRefusalStaysAlthoughTheRequestItRefusedRollsBack() {
        aliceIsTheCaller();

        assertThrows(IllegalStateException.class, () -> changes.changeThenRefuse("Giải trí"));

        assertFalse(categoryNamed("Giải trí"));
        List<ActionLog> rows = actionLogRepository.findAll();
        assertEquals(1, rows.size(), "written in a transaction of its own, committed before the request rolled back");
        assertEquals("AUTH_ACCESS_DENIED", rows.get(0).getAction());
        assertEquals("FAILURE", rows.get(0).getResult());
        assertEquals(41L, rows.get(0).getUserId(), "the caller, looked up when the row was written");
        assertEquals("alice@example.com", rows.get(0).getUserEmail());
    }

    @Test
    void aRowOfItsOwnThatCannotBeWrittenLeavesTheRequestAsItWas() {
        aliceIsTheCaller();
        theDatabaseRefusesEveryRow();

        Long id = assertDoesNotThrow(() -> changes.changeAndPublish("Giáo dục"));

        assertNotNull(id);
        assertTrue(categoryNamed("Giáo dục"), "the request committed its change: the failed row was not its business");
        assertEquals(0, actionLogRepository.count());
    }
}
