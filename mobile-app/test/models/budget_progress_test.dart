import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/models/budget_model.dart';
import 'package:finance_ai_app/models/budget_progress.dart';
import 'package:finance_ai_app/models/transaction_model.dart';

BudgetModel _budget({
  int id = 1,
  int? categoryId = 10,
  String? categoryName = 'Ăn uống',
  double amount = 1000000,
  String periodType = BudgetPeriodType.month,
  String startDate = '2026-09-01',
  String endDate = '2026-09-30',
}) => BudgetModel(
  id: id,
  categoryId: categoryId,
  categoryName: categoryName,
  amount: amount,
  periodType: periodType,
  startDate: startDate,
  endDate: endDate,
);

TransactionModel _expense(
  double amount,
  DateTime date, {
  String category = 'Ăn uống',
}) => TransactionModel(
  title: 'chi',
  amount: amount,
  date: date,
  category: category,
  isExpense: true,
);

TransactionModel _income(
  double amount,
  DateTime date, {
  String category = 'Ăn uống',
}) => TransactionModel(
  title: 'thu',
  amount: amount,
  date: date,
  category: category,
  isExpense: false,
);

BudgetProgress _spent(double spent, {double limit = 1000000}) => BudgetProgress(
  budget: _budget(amount: limit),
  spent: spent,
);

void main() {
  group('what counts as spending', () {
    test('sums the expenses of the category made inside the period', () {
      final progress = BudgetProgress.of(_budget(), [
        _expense(300000, DateTime(2026, 9, 3, 12)),
        _expense(200000, DateTime(2026, 9, 20, 8)),
      ]);

      expect(progress.spent, 500000);
    });

    test('income is not spending', () {
      final progress = BudgetProgress.of(_budget(), [
        _expense(100000, DateTime(2026, 9, 3)),
        _income(900000, DateTime(2026, 9, 3)),
      ]);

      expect(progress.spent, 100000);
    });

    test(
      'the first and the last day of the period count, from midnight to the last second',
      () {
        final progress = BudgetProgress.of(_budget(), [
          _expense(1, DateTime(2026, 9, 1, 0, 0, 0)),
          _expense(10, DateTime(2026, 9, 30, 23, 59, 59)),
          _expense(100, DateTime(2026, 8, 31, 23, 59, 59)),
          _expense(1000, DateTime(2026, 10, 1, 0, 0, 0)),
        ]);

        expect(progress.spent, 11);
      },
    );

    test(
      'another category does not count, and names compare without case or surrounding spaces',
      () {
        final progress = BudgetProgress.of(_budget(), [
          _expense(100, DateTime(2026, 9, 3), category: 'Di chuyển'),
          _expense(10, DateTime(2026, 9, 3), category: '  ăn uống '),
          _expense(1, DateTime(2026, 9, 3), category: 'ĂN UỐNG'),
        ]);

        expect(progress.spent, 11);
      },
    );

    test('a budget without a category counts every expense of the period', () {
      final progress =
          BudgetProgress.of(_budget(categoryId: null, categoryName: null), [
            _expense(100, DateTime(2026, 9, 3), category: 'Di chuyển'),
            _expense(10, DateTime(2026, 9, 4), category: 'Ăn uống'),
            _income(1000, DateTime(2026, 9, 4)),
          ]);

      expect(progress.spent, 110);
    });

    test('a weekly budget only counts its own week', () {
      final week = _budget(
        periodType: BudgetPeriodType.week,
        startDate: '2026-09-14',
        endDate: '2026-09-20',
      );
      final progress = BudgetProgress.of(week, [
        _expense(1, DateTime(2026, 9, 13, 23)),
        _expense(10, DateTime(2026, 9, 14, 9)),
        _expense(100, DateTime(2026, 9, 20, 22)),
        _expense(1000, DateTime(2026, 9, 21, 1)),
      ]);

      expect(progress.spent, 110);
    });

    test('nothing spent is zero', () {
      expect(BudgetProgress.of(_budget(), const []).spent, 0);
    });
  });

  group('percentages', () {
    test('percent, label, remaining and bar value of a budget in use', () {
      final progress = _spent(850000);

      expect(progress.percent, 85);
      expect(progress.percentLabel, 85);
      expect(progress.remaining, 150000);
      expect(progress.barValue, 0.85);
    });

    test(
      'an exceeded budget shows more than 100%, a negative remainder and a full bar',
      () {
        final progress = _spent(1500000);

        expect(progress.percentLabel, 150);
        expect(progress.remaining, -500000);
        expect(progress.barValue, 1.0);
      },
    );

    test(
      'the label rounds down, so 80% never shows before the warning starts',
      () {
        expect(_spent(799999).percentLabel, 79);
        expect(_spent(799999).level, BudgetAlertLevel.none);
        expect(_spent(999999).percentLabel, 99);
        expect(_spent(1000000).percentLabel, 100);
      },
    );

    test(
      'binary floating point does not shave a percent off (0.29 * 100 is 28.999999999999996)',
      () {
        expect(_spent(290, limit: 1000).percentLabel, 29);
        expect(_spent(290, limit: 1000).percent, 29);
        expect(_spent(580, limit: 2000).percentLabel, 29);
      },
    );

    test('a limit that is not positive gives 0% and no alert', () {
      for (final limit in [0.0, -5.0]) {
        final progress = _spent(500, limit: limit);
        expect(progress.percent, 0);
        expect(progress.level, BudgetAlertLevel.none);
        expect(progress.barValue, 0);
      }
    });
  });

  group('alert level', () {
    test('changes exactly at 80% and at 100% of the limit', () {
      final expected = {
        0.0: BudgetAlertLevel.none,
        500000.0: BudgetAlertLevel.none,
        799999.0: BudgetAlertLevel.none,
        800000.0: BudgetAlertLevel.warning,
        999999.0: BudgetAlertLevel.warning,
        1000000.0: BudgetAlertLevel.exceeded,
        1000001.0: BudgetAlertLevel.exceeded,
        5000000.0: BudgetAlertLevel.exceeded,
      };
      expected.forEach((spent, level) {
        expect(_spent(spent).level, level, reason: 'spent $spent of 1000000');
      });
    });

    test('is exact for limits that do not divide evenly', () {
      expect(
        _spent(2.4, limit: 3).level,
        BudgetAlertLevel.warning,
      ); // exactly 80%
      expect(_spent(2.3999, limit: 3).level, BudgetAlertLevel.none);
      expect(_spent(3, limit: 3).level, BudgetAlertLevel.exceeded);
    });

    test('the thresholds are the ones of the requirement', () {
      expect(BudgetProgress.warningPercent, 80);
      expect(BudgetProgress.exceededPercent, 100);
    });
  });

  group('choosing the budgets', () {
    final september = _budget(id: 1);
    final week = _budget(
      id: 2,
      periodType: BudgetPeriodType.week,
      startDate: '2026-09-14',
      endDate: '2026-09-20',
    );
    final october = _budget(
      id: 3,
      startDate: '2026-10-01',
      endDate: '2026-10-31',
    );
    final all = [september, week, october];

    test(
      'activeOn keeps the budgets whose period contains the day, ends included',
      () {
        List<int?> ids(DateTime day) => BudgetProgress.activeOn(
          day,
          all,
          const [],
        ).map((p) => p.budget.id).toList();

        expect(ids(DateTime(2026, 9, 16, 15)), [1, 2]);
        expect(ids(DateTime(2026, 9, 14)), [1, 2]);
        expect(ids(DateTime(2026, 9, 20, 23, 59)), [1, 2]);
        expect(ids(DateTime(2026, 9, 21)), [1]);
        expect(ids(DateTime(2026, 10, 1)), [3]);
        expect(ids(DateTime(2026, 11, 1)), isEmpty);
      },
    );

    test(
      'overlapping keeps the budgets that share at least one day with the range',
      () {
        List<int?> ids(DateTime from, DateTime to) =>
            BudgetProgress.overlapping(
              from,
              to,
              all,
              const [],
            ).map((p) => p.budget.id).toList();

        expect(ids(DateTime(2026, 9, 1), DateTime(2026, 9, 30)), [1, 2]);
        expect(ids(DateTime(2026, 9, 21), DateTime(2026, 9, 27)), [1]);
        expect(ids(DateTime(2026, 9, 28), DateTime(2026, 10, 4)), [1, 3]);
        expect(ids(DateTime(2026, 10, 1), DateTime(2026, 10, 31)), [3]);
        expect(ids(DateTime(2026, 12, 1), DateTime(2026, 12, 31)), isEmpty);
      },
    );

    test(
      'each budget is measured against its own period, not the range that was asked for',
      () {
        final transactions = [
          _expense(100, DateTime(2026, 9, 2)),
          _expense(10, DateTime(2026, 9, 15)),
        ];

        final progress = BudgetProgress.overlapping(
          DateTime(2026, 9, 1),
          DateTime(2026, 9, 30),
          [september, week],
          transactions,
        );

        expect(progress[0].spent, 110); // the month
        expect(progress[1].spent, 10); // the week
      },
    );
  });
}
