import 'package:finance_ai_app/models/budget_model.dart';
import 'package:finance_ai_app/models/transaction_model.dart';

/// How far a budget is used up: below the warning threshold, at or past it, or at or past the limit itself.
enum BudgetAlertLevel { none, warning, exceeded }

/// How much of a budget has been spent within the budget's own period (its startDate..endDate).
class BudgetProgress {
  /// Share of the limit, in percent, from which a budget counts as close to its limit, and as exceeded.
  static const int warningPercent = 80;
  static const int exceededPercent = 100;

  final BudgetModel budget;
  final double spent;

  const BudgetProgress({required this.budget, required this.spent});

  /// The expenses of [transactions] made during the period of [budget], both end days included and, when the budget
  /// has a category, only those of that category (names compare without case or surrounding spaces).
  factory BudgetProgress.of(
    BudgetModel budget,
    Iterable<TransactionModel> transactions,
  ) {
    final start = budget.start;
    final end = budget.end;
    final categoryName = budget.categoryName;
    final category = categoryName == null ? null : _normalise(categoryName);

    var spent = 0.0;
    for (final transaction in transactions) {
      if (!transaction.isExpense) continue;
      final day = DateTime(
        transaction.date.year,
        transaction.date.month,
        transaction.date.day,
      );
      if (day.isBefore(start) || day.isAfter(end)) continue;
      if (category != null && _normalise(transaction.category) != category) {
        continue;
      }
      spent += transaction.amount;
    }
    return BudgetProgress(budget: budget, spent: spent);
  }

  /// The progress of every budget whose period contains [day].
  static List<BudgetProgress> activeOn(
    DateTime day,
    Iterable<BudgetModel> budgets,
    Iterable<TransactionModel> transactions,
  ) => [
    for (final budget in budgets)
      if (budget.coversDay(day)) BudgetProgress.of(budget, transactions),
  ];

  /// The progress of every budget whose period shares a day with [from]..[to].
  static List<BudgetProgress> overlapping(
    DateTime from,
    DateTime to,
    Iterable<BudgetModel> budgets,
    Iterable<TransactionModel> transactions,
  ) => [
    for (final budget in budgets)
      if (budget.overlaps(from, to)) BudgetProgress.of(budget, transactions),
  ];

  static String _normalise(String name) => name.trim().toLowerCase();

  double get limit => budget.amount;

  /// Negative once the limit is passed.
  double get remaining => limit - spent;

  /// Share of the limit that is spent, in percent; above 100 when exceeded, and 0 when there is no positive limit.
  double get percent => limit > 0 ? spent * 100 / limit : 0;

  /// The percent as shown to the user. It is rounded down, so "80%" never appears before the warning level starts.
  int get percentLabel => percent.floor();

  /// The value of a progress bar: between 0 and 1.
  double get barValue => (percent / 100).clamp(0.0, 1.0);

  BudgetAlertLevel get level {
    if (limit <= 0) return BudgetAlertLevel.none;
    // Multiplying instead of dividing keeps the comparison exact at the thresholds.
    if (spent * 100 >= limit * exceededPercent) {
      return BudgetAlertLevel.exceeded;
    }
    if (spent * 100 >= limit * warningPercent) return BudgetAlertLevel.warning;
    return BudgetAlertLevel.none;
  }
}
