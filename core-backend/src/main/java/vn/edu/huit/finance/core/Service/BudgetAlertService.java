package vn.edu.huit.finance.core.Service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Entity.Budget;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.NotificationType;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Repository.BudgetRepository;
import vn.edu.huit.finance.core.Repository.TransactionRepository;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Raises a notification when a transaction moves one of its owner's budgets up to 80% or 100% of its limit. TransactionService
 * calls it inside the database transaction that saves the change, so the notification is committed or rolled back
 * together with the transaction and the wallet. Only expenses count towards a budget, and a notification is raised when
 * the change takes a budget from below a level to that level or above: it does not repeat while the spending stays
 * there, and a change that jumps over both levels raises only the higher one.
 */
@Service
public class BudgetAlertService {

    private static final BigDecimal PERCENT_BASE = BigDecimal.valueOf(100);
    private static final String ALL_CATEGORIES_LABEL = "tất cả danh mục";
    private static final String MONEY_PATTERN = "#,##0";
    private static final char MONEY_GROUPING_SEPARATOR = '.';
    private static final String MSG_BUDGET_WARNING = "Bạn đã chi %s / %s VNĐ, đạt %d%% ngân sách %s.";
    private static final String MSG_BUDGET_EXCEEDED = "Bạn đã chi %s / %s VNĐ, vượt ngân sách %s.";

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private NotificationService notificationService;

    /**
     * What a transaction counted for in the budgets at some moment: an expense of {@code amount} on {@code date}, in one
     * category. TransactionService takes one before it changes a transaction, so the alert can tell what the change did.
     */
    public record SpendingSnapshot(BigDecimal amount, boolean expense, LocalDate date, Long categoryId) {

        /** A transaction that did not exist yet: it counted for nothing. */
        private static final SpendingSnapshot NONE = new SpendingSnapshot(BigDecimal.ZERO, false, null, null);

        public static SpendingSnapshot of(Transaction transaction) {
            return new SpendingSnapshot(transaction.getAmount(), transaction.isExpense(),
                    transaction.getTransactionDate().toLocalDate(), transaction.getCategory().getId());
        }

        /** How much of this spending is inside the budget: all of it when the day and the category match, else nothing. */
        BigDecimal contributionTo(Budget budget) {
            if (!expense || date.isBefore(budget.getStartDate()) || date.isAfter(budget.getEndDate())) {
                return BigDecimal.ZERO;
            }
            Category category = budget.getCategory();
            return category == null || category.getId().equals(categoryId) ? amount : BigDecimal.ZERO;
        }
    }

    /** For a transaction that has just been saved. */
    @Transactional
    public void notifyAfterCreate(Transaction created) {
        notifyOnCrossing(created, SpendingSnapshot.NONE);
    }

    /** For a transaction that has just been changed; {@code before} is what it counted for before the change. */
    @Transactional
    public void notifyAfterUpdate(Transaction updated, SpendingSnapshot before) {
        notifyOnCrossing(updated, before);
    }

    private void notifyOnCrossing(Transaction transaction, SpendingSnapshot before) {
        // Income never uses up a budget, and an expense that became an income or left every budget only lowers spending.
        if (!transaction.isExpense()) {
            return;
        }

        Long userId = transaction.getUser().getId();
        List<Budget> budgets = budgetRepository.findCoveringDate(
                userId,
                transaction.getTransactionDate().toLocalDate(),
                List.of(Budget.ALL_CATEGORIES_KEY, transaction.getCategory().getId()));

        for (Budget budget : budgets) {
            // The total already includes the saved transaction; taking it out and putting back what it counted for
            // before gives the total as it was before the change, without a second query.
            BigDecimal spentAfter = spentWithin(budget, userId);
            BigDecimal spentBefore = spentAfter.subtract(transaction.getAmount()).add(before.contributionTo(budget));

            // The alert is about the budget and the transaction that crossed it, never about a receipt.
            newlyReached(spentBefore, spentAfter, budget.getAmount()).ifPresent(type -> notificationService.create(
                    transaction.getUser(), type, messageFor(type, budget, spentAfter),
                    transaction.getId(), budget.getId(), null));
        }
    }

    /** Expenses of the user inside the period of the budget, both end days included, in the budget's category. */
    private BigDecimal spentWithin(Budget budget, Long userId) {
        LocalDateTime periodStart = budget.getStartDate().atStartOfDay();
        LocalDateTime periodEnd = budget.getEndDate().plusDays(1).atStartOfDay();
        Category category = budget.getCategory();

        BigDecimal spent = category == null
                ? transactionRepository.sumExpensesBetween(userId, periodStart, periodEnd)
                : transactionRepository.sumExpensesInCategoryBetween(userId, category.getId(), periodStart, periodEnd);
        return spent == null ? BigDecimal.ZERO : spent;
    }

    /** The level the change moved the budget up to: the one reached now, when it is higher than the one it was at. */
    private static Optional<NotificationType> newlyReached(BigDecimal spentBefore, BigDecimal spentAfter, BigDecimal limit) {
        Optional<NotificationType> after = highestReached(spentAfter, limit);
        if (after.isEmpty()) {
            return Optional.empty();
        }
        Optional<NotificationType> before = highestReached(spentBefore, limit);
        boolean alreadyThere = before.isPresent()
                && before.get().getThresholdPercent() >= after.get().getThresholdPercent();
        return alreadyThere ? Optional.empty() : after;
    }

    /** The highest level whose share of the limit has been spent; a level counts as soon as the spending equals its share. */
    private static Optional<NotificationType> highestReached(BigDecimal spent, BigDecimal limit) {
        // Compared as spent * 100 >= limit * percent, so no division can round a threshold the wrong way.
        BigDecimal spentPercentPoints = spent.multiply(PERCENT_BASE);
        NotificationType reached = null;
        for (NotificationType type : NotificationType.budgetLevels()) {
            BigDecimal levelPercentPoints = limit.multiply(BigDecimal.valueOf(type.getThresholdPercent()));
            if (spentPercentPoints.compareTo(levelPercentPoints) >= 0) {
                reached = type;
            }
        }
        return Optional.ofNullable(reached);
    }

    private static String messageFor(NotificationType type, Budget budget, BigDecimal spent) {
        String spentText = formatMoney(spent);
        String limitText = formatMoney(budget.getAmount());
        String scope = budget.getCategory() == null ? ALL_CATEGORIES_LABEL : budget.getCategory().getName();

        return switch (type) {
            case BUDGET_WARNING -> String.format(Locale.ROOT, MSG_BUDGET_WARNING, spentText, limitText,
                    type.getThresholdPercent(), scope);
            case BUDGET_EXCEEDED -> String.format(Locale.ROOT, MSG_BUDGET_EXCEEDED, spentText, limitText, scope);
            default -> throw new IllegalArgumentException("Not a budget level: " + type);
        };
    }

    /** 1600000 becomes "1.600.000"; whatever the JVM locale is. A DecimalFormat is not thread-safe, so each call makes its own. */
    private static String formatMoney(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(MONEY_GROUPING_SEPARATOR);
        return new DecimalFormat(MONEY_PATTERN, symbols).format(amount);
    }
}
