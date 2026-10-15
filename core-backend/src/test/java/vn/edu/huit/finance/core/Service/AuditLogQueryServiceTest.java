package vn.edu.huit.finance.core.Service;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import vn.edu.huit.finance.core.Dto.AuditLogFilterRequestDto;
import vn.edu.huit.finance.core.Dto.AuditLogPageResponseDto;
import vn.edu.huit.finance.core.Dto.AuditLogResponseDto;
import vn.edu.huit.finance.core.Dto.AuditSummaryRowDto;
import vn.edu.huit.finance.core.Entity.ActionLog;
import vn.edu.huit.finance.core.Repository.ActionLogRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What administrators can read of the activity log, on H2: always newest first, always bounded, filtered by action
 * (legacy codes included), actor, target and time, and never more than a fixed number of statements, whatever the size
 * of the page (ActionLog has no relation that could be loaded lazily).
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Import(AuditLogQueryService.class)
class AuditLogQueryServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 30, 12, 0);
    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";

    @Autowired private AuditLogQueryService service;
    @Autowired private ActionLogRepository repository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void theClockIsOneDayAfterT0() {
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(T0.plusDays(1).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
    }

    private ActionLog row(String action, Long userId, String email, String targetType, Long targetId, LocalDateTime at) {
        return repository.saveAndFlush(ActionLog.builder()
                .action(action).userId(userId).userEmail(email).targetType(targetType).targetId(targetId)
                .description(action + " #" + targetId).ipAddress("203.0.113.7").timestamp(at)
                .build());
    }

    private static AuditLogFilterRequestDto filter() {
        return new AuditLogFilterRequestDto();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static List<Long> idsOf(AuditLogPageResponseDto page) {
        return page.getContent().stream().map(AuditLogResponseDto::getId).toList();
    }

    @Test
    void rowsComeNewestFirst_andRowsOfTheSameInstantByTheirIdDescending() {
        ActionLog older = row("USER_UPDATED", 1L, ALICE, "USER", 1L, T0.minusHours(1));
        ActionLog sameInstantFirst = row("USER_UPDATED", 1L, ALICE, "USER", 1L, T0);
        ActionLog sameInstantSecond = row("USER_UPDATED", 1L, ALICE, "USER", 1L, T0);

        AuditLogPageResponseDto page = service.search(filter());

        assertEquals(List.of(sameInstantSecond.getId(), sameInstantFirst.getId(), older.getId()), idsOf(page));
    }

    @Test
    void anActionIsFoundWithTheRowsOlderBuildsWroteUnderItsLegacyCodes() {
        ActionLog legacy = row("LOGIN_FAILED", null, BOB, null, null, T0.minusDays(3));
        ActionLog current = row("AUTH_LOGIN_FAILED", null, BOB, null, null, T0);
        row("AUTH_LOGIN_SUCCESS", 2L, BOB, "USER", 2L, T0);
        AuditLogFilterRequestDto byAction = filter();
        byAction.setAction("AUTH_LOGIN_FAILED");

        AuditLogPageResponseDto page = service.search(byAction);

        assertEquals(List.of(current.getId(), legacy.getId()), idsOf(page));
        AuditLogResponseDto old = page.getContent().get(1);
        assertEquals("AUTH_LOGIN_FAILED", old.getAction(), "shown under today's name");
        assertEquals("LOGIN_FAILED", old.getStoredAction(), "and never rewritten");
        assertEquals("FAILURE", old.getResult(), "the result its action has, for a row written before the column");
        assertEquals("Đăng nhập thất bại", old.getActionLabel());
    }

    @Test
    void aLegacyCodeCanBeAskedForToo() {
        row("CREATE_PLAN", null, "admin@example.com", null, null, T0);
        AuditLogFilterRequestDto byLegacyCode = filter();
        byLegacyCode.setAction("create_plan");

        AuditLogPageResponseDto page = service.search(byLegacyCode);

        assertEquals(1, page.getTotalElements());
        assertEquals("PLAN_CREATED", page.getContent().get(0).getAction());
        assertEquals("Gói hội viên", page.getContent().get(0).getTargetLabel(), "the kind its action is about");
    }

    @Test
    void theFiltersApplyTogether_actorTargetAndAnInclusiveTimeRange() {
        ActionLog match = row("TRANSACTION_UPDATED", 1L, ALICE, "TRANSACTION", 9L, T0);
        row("TRANSACTION_UPDATED", 2L, BOB, "TRANSACTION", 9L, T0);
        row("TRANSACTION_UPDATED", 1L, ALICE, "TRANSACTION", 8L, T0);
        row("TRANSACTION_UPDATED", 1L, ALICE, "RECEIPT", 9L, T0);
        row("TRANSACTION_UPDATED", 1L, ALICE, "TRANSACTION", 9L, T0.minusSeconds(1));
        AuditLogFilterRequestDto all = filter();
        all.setUserId(1L);
        all.setEmail("  " + ALICE + " ");
        all.setTargetType("transaction");
        all.setTargetId(9L);
        all.setFrom(T0);
        all.setTo(T0);

        AuditLogPageResponseDto page = service.search(all);

        assertEquals(List.of(match.getId()), idsOf(page));
    }

    @Test
    void aTargetTypeAloneFindsEveryRecordOfThatKind() {
        row("BUDGET_CREATED", 1L, ALICE, "BUDGET", 1L, T0);
        row("BUDGET_DELETED", 1L, ALICE, "BUDGET", 2L, T0);
        row("USER_UPDATED", 1L, ALICE, "USER", 1L, T0);
        AuditLogFilterRequestDto budgets = filter();
        budgets.setTargetType("BUDGET");

        assertEquals(2, service.search(budgets).getTotalElements());
    }

    @Test
    void aPageHoldsAtMostTheMaximumWhateverIsAskedFor() {
        for (int i = 0; i < AuditLogFilterRequestDto.MAX_SIZE + 5; i++) {
            row("USER_UPDATED", 1L, ALICE, "USER", 1L, T0.minusMinutes(i));
        }
        AuditLogFilterRequestDto huge = filter();
        huge.setSize(1_000_000);

        AuditLogPageResponseDto page = service.search(huge);

        assertEquals(AuditLogFilterRequestDto.MAX_SIZE, page.getContent().size());
        assertEquals(AuditLogFilterRequestDto.MAX_SIZE + 5, page.getTotalElements());
        assertTrue(page.isHasNext());
    }

    @Test
    void aPageAfterTheLastIsEmptyButTellsHowManyRowsThereAre() {
        row("USER_UPDATED", 1L, ALICE, "USER", 1L, T0);
        AuditLogFilterRequestDto beyond = filter();
        beyond.setPage(3);

        AuditLogPageResponseDto page = service.search(beyond);

        assertEquals(List.of(), page.getContent());
        assertEquals(1, page.getTotalElements());
        assertTrue(page.isHasPrevious());
        assertFalse(page.isHasNext());
    }

    @Test
    void aPageCostsTwoStatements_whateverItsSize() {
        for (int i = 0; i < 30; i++) {
            row(i % 2 == 0 ? "USER_UPDATED" : "AUTH_LOGIN_FAILED", (long) i, ALICE, "USER", (long) i, T0.minusMinutes(i));
        }
        AuditLogFilterRequestDto page = filter();
        page.setSize(25);
        statistics().clear();

        service.search(page);

        assertEquals(2, statistics().getPrepareStatementCount(), "the page and its count, nothing per row");
    }

    @Test
    void theTabsOfThePageSplitSecurityEventsFromTheRest_legacyCodesIncluded_inOneBoundedStatementEach() {
        ActionLog oldLogin = row("LOGIN_SUCCESS", 1L, ALICE, null, null, T0.minusDays(2));
        ActionLog oldLogout = row("LOGOUT", 1L, ALICE, null, null, T0.minusDays(1));
        ActionLog denied = row("AUTH_ACCESS_DENIED", 1L, ALICE, null, null, T0);
        ActionLog oldPlan = row("CREATE_PLAN", 3L, "admin@example.com", null, null, T0.minusDays(2));
        ActionLog created = row("TRANSACTION_CREATED", 1L, ALICE, "TRANSACTION", 5L, T0);
        ActionLog unknown = row("SOMETHING_ELSE", null, null, null, null, T0.minusDays(3));
        statistics().clear();

        List<Long> security = service.latestSecurityEvents().stream().map(AuditLogResponseDto::getId).toList();
        List<Long> others = service.latestOtherEvents().stream().map(AuditLogResponseDto::getId).toList();

        assertEquals(2, statistics().getPrepareStatementCount());
        assertEquals(List.of(denied.getId(), oldLogout.getId(), oldLogin.getId()), security);
        assertEquals(List.of(created.getId(), oldPlan.getId(), unknown.getId()), others,
                "a code no action knows is still shown, with the data changes");
    }

    @Test
    void theTabsShowAtMostTheLatestRows() {
        for (int i = 0; i < AuditLogQueryService.LATEST_LIMIT + 10; i++) {
            row("AUTH_LOGIN_FAILED", null, BOB, null, null, T0.minusSeconds(i));
        }

        List<AuditLogResponseDto> latest = service.latestSecurityEvents();

        assertEquals(AuditLogQueryService.LATEST_LIMIT, latest.size());
        assertEquals(T0, latest.get(0).getTimestamp());
    }

    @Test
    void theSummaryCountsWhoDidWhatWithinItsWindow_mostFrequentFirst_inOneStatement() {
        row("AUTH_LOGIN_FAILED", null, BOB, null, null, T0);
        row("AUTH_LOGIN_FAILED", null, BOB, null, null, T0.minusHours(2));
        row("AUTH_LOGIN_FAILED", null, BOB, null, null, T0.minusHours(3));
        row("USER_UPDATED", 1L, ALICE, "USER", 1L, T0.minusHours(1));
        row("USER_UPDATED", 1L, ALICE, "USER", 1L, T0.minusDays(AuditLogQueryService.SUMMARY_WINDOW_DAYS + 2));
        statistics().clear();

        List<AuditSummaryRowDto> summary = service.summary();

        assertEquals(1, statistics().getPrepareStatementCount());
        assertEquals(2, summary.size());
        assertEquals(BOB, summary.get(0).getActorEmail());
        assertEquals("AUTH_LOGIN_FAILED", summary.get(0).getAction());
        assertEquals(3, summary.get(0).getTotal());
        assertEquals(T0, summary.get(0).getLastAt());
        assertEquals(1, summary.get(1).getTotal(), "the row older than the window is not counted");
        assertEquals("Tài khoản", summary.get(1).getTargetLabel());
    }

    @Test
    void theTableHasTheIndexesTheseReadsUse() {
        Set<String> indexes = jdbc.queryForList(
                        "select index_name from information_schema.indexes where table_name = 'ACTION_LOGS'", String.class)
                .stream().map(String::toLowerCase).collect(Collectors.toSet());

        assertTrue(indexes.containsAll(Set.of("idx_action_logs_timestamp", "idx_action_logs_action_timestamp",
                "idx_action_logs_user_id_timestamp", "idx_action_logs_user_email_timestamp",
                "idx_action_logs_target_timestamp")), indexes.toString());
    }
}
