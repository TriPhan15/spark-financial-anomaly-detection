package vn.edu.huit.finance.core.Service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.AuditLogFilterRequestDto;
import vn.edu.huit.finance.core.Dto.AuditLogPageResponseDto;
import vn.edu.huit.finance.core.Dto.AuditLogResponseDto;
import vn.edu.huit.finance.core.Dto.AuditSummaryRowDto;
import vn.edu.huit.finance.core.Entity.ActionLog;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.AuditCategory;
import vn.edu.huit.finance.core.Entity.AuditTargetType;
import vn.edu.huit.finance.core.Repository.ActionLogRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads of the activity log, for administrators only (the callers check that). Every read is bounded and newest first
 * (timestamp, then id, both descending, which the indexes of action_logs serve): a filtered page of at most
 * {@value AuditLogFilterRequestDto#MAX_SIZE} rows, the latest {@value #LATEST_LIMIT} rows of a tab, or the summary of
 * the last {@value #SUMMARY_WINDOW_DAYS} days. Nothing is cached: the log changes with every request and an
 * administrator expects to see the row that was just written.
 */
@Service
public class AuditLogQueryService {

    public static final int LATEST_LIMIT = 200;
    public static final int SUMMARY_LIMIT = 200;
    public static final int SUMMARY_WINDOW_DAYS = 30;

    private static final String ACTION = "action";
    private static final String USER_ID = "userId";
    private static final String USER_EMAIL = "userEmail";
    private static final String TARGET_TYPE = "targetType";
    private static final String TARGET_ID = "targetId";
    private static final String TIMESTAMP = "timestamp";
    private static final String ID = "id";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc(TIMESTAMP), Sort.Order.desc(ID));
    private static final Duration SUMMARY_WINDOW = Duration.ofDays(SUMMARY_WINDOW_DAYS);

    @Autowired
    private ActionLogRepository actionLogRepository;

    // Not injected: the system clock is what production uses; tests replace it to control the summary window.
    private Clock clock = Clock.systemDefaultZone();

    /** One page of the rows that match every filter given; a page never holds more than the maximum size. */
    @Transactional(readOnly = true)
    public AuditLogPageResponseDto search(AuditLogFilterRequestDto filter) {
        int size = Math.min(Math.max(filter.getSize(), AuditLogFilterRequestDto.MIN_SIZE), AuditLogFilterRequestDto.MAX_SIZE);
        int page = Math.min(Math.max(filter.getPage(), AuditLogFilterRequestDto.FIRST_PAGE), AuditLogFilterRequestDto.MAX_PAGE);
        Page<ActionLog> rows = actionLogRepository.findAll(matching(filter), PageRequest.of(page, size, NEWEST_FIRST));
        return AuditLogPageResponseDto.from(rows);
    }

    /** The newest sign-ins, session and credential events and refused requests. */
    @Transactional(readOnly = true)
    public List<AuditLogResponseDto> latestSecurityEvents() {
        return toDtos(actionLogRepository.findByActionIn(securityCodes(), latestPage()));
    }

    /** The newest rows of every other action: data changes and system operations, legacy and unknown codes included. */
    @Transactional(readOnly = true)
    public List<AuditLogResponseDto> latestOtherEvents() {
        return toDtos(actionLogRepository.findByActionNotIn(securityCodes(), latestPage()));
    }

    /** Who did what how often in the last days, the most frequent first. */
    @Transactional(readOnly = true)
    public List<AuditSummaryRowDto> summary() {
        LocalDateTime since = LocalDateTime.now(clock).minus(SUMMARY_WINDOW);
        return actionLogRepository.summarizeSince(since, PageRequest.of(0, SUMMARY_LIMIT)).stream()
                .map(AuditSummaryRowDto::from)
                .toList();
    }

    private static Set<String> securityCodes() {
        return AuditAction.storedCodesOf(AuditCategory.SECURITY);
    }

    private static Pageable latestPage() {
        return PageRequest.of(0, LATEST_LIMIT, NEWEST_FIRST);
    }

    private static List<AuditLogResponseDto> toDtos(List<ActionLog> rows) {
        return rows.stream().map(AuditLogResponseDto::from).toList();
    }

    /** One predicate per filter that was given; none at all lists everything. */
    private static Specification<ActionLog> matching(AuditLogFilterRequestDto filter) {
        List<Specification<ActionLog>> predicates = new ArrayList<>();
        filter.actionFilter().ifPresent(action ->
                predicates.add((root, query, builder) -> root.get(ACTION).in(action.getStoredCodes())));
        if (filter.getUserId() != null) {
            predicates.add((root, query, builder) -> builder.equal(root.get(USER_ID), filter.getUserId()));
        }
        filter.emailFilter().ifPresent(email ->
                predicates.add((root, query, builder) -> builder.equal(root.get(USER_EMAIL), email)));
        filter.targetTypeFilter().map(AuditTargetType::name).ifPresent(targetType ->
                predicates.add((root, query, builder) -> builder.equal(root.get(TARGET_TYPE), targetType)));
        if (filter.getTargetId() != null) {
            predicates.add((root, query, builder) -> builder.equal(root.get(TARGET_ID), filter.getTargetId()));
        }
        if (filter.getFrom() != null) {
            predicates.add((root, query, builder) ->
                    builder.greaterThanOrEqualTo(root.<LocalDateTime>get(TIMESTAMP), filter.getFrom()));
        }
        if (filter.getTo() != null) {
            predicates.add((root, query, builder) ->
                    builder.lessThanOrEqualTo(root.<LocalDateTime>get(TIMESTAMP), filter.getTo()));
        }
        return Specification.allOf(predicates);
    }
}
