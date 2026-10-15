import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/models/budget_model.dart';

// The JSON mirrors the contract of core-backend's /api/budgets (BudgetResponseDto and BudgetRequestDto).
void main() {
  group('BudgetPeriodType.rangeContaining', () {
    test('a week runs from Monday to Sunday, whichever day of it is given', () {
      for (final day in [
        DateTime(2026, 9, 14), // Monday
        DateTime(2026, 9, 16), // Wednesday
        DateTime(2026, 9, 20), // Sunday
      ]) {
        final range = BudgetPeriodType.rangeContaining(
          BudgetPeriodType.week,
          day,
        );
        expect(range.start, DateTime(2026, 9, 14), reason: '$day');
        expect(range.end, DateTime(2026, 9, 20), reason: '$day');
      }
    });

    test('a week can cross a month and a year boundary', () {
      final overMonth = BudgetPeriodType.rangeContaining(
        BudgetPeriodType.week,
        DateTime(2026, 9, 30),
      );
      expect(
        (overMonth.start, overMonth.end),
        (DateTime(2026, 9, 28), DateTime(2026, 10, 4)),
      );

      final overYear = BudgetPeriodType.rangeContaining(
        BudgetPeriodType.week,
        DateTime(2026, 1, 1),
      );
      expect(
        (overYear.start, overYear.end),
        (DateTime(2025, 12, 29), DateTime(2026, 1, 4)),
      );
    });

    test(
      'a month runs from its first to its last day, leap years included',
      () {
        final expected = {
          DateTime(2026, 9, 16): (DateTime(2026, 9, 1), DateTime(2026, 9, 30)),
          DateTime(2026, 2, 10): (DateTime(2026, 2, 1), DateTime(2026, 2, 28)),
          DateTime(2028, 2, 10): (DateTime(2028, 2, 1), DateTime(2028, 2, 29)),
          DateTime(2026, 12, 31): (
            DateTime(2026, 12, 1),
            DateTime(2026, 12, 31),
          ),
        };
        expected.forEach((day, bounds) {
          final range = BudgetPeriodType.rangeContaining(
            BudgetPeriodType.month,
            day,
          );
          expect((range.start, range.end), bounds, reason: '$day');
        });
      },
    );

    test(
      'every day of a year falls inside a range the backend accepts (at most 7 days, resp. 31)',
      () {
        for (
          var day = DateTime(2026, 1, 1);
          day.year == 2026;
          day = DateTime(day.year, day.month, day.day + 1)
        ) {
          for (final entry in {
            BudgetPeriodType.week: 7,
            BudgetPeriodType.month: 31,
          }.entries) {
            final range = BudgetPeriodType.rangeContaining(entry.key, day);
            final days = range.end.difference(range.start).inDays + 1;
            expect(
              days <= entry.value,
              isTrue,
              reason: '${entry.key} of $day lasts $days days',
            );
            expect(
              range.start.isAfter(day),
              isFalse,
              reason: '${entry.key} of $day starts after it',
            );
            expect(
              range.end.isBefore(day),
              isFalse,
              reason: '${entry.key} of $day ends before it',
            );
          }
        }
      },
    );
  });

  group('BudgetModel and the backend JSON', () {
    test('fromJson reads a budget with a category', () {
      final budget = BudgetModel.fromJson({
        'id': 7,
        'categoryId': 10,
        'categoryName': 'Ăn uống',
        'amount': 2000000.00,
        'periodType': 'MONTH',
        'startDate': '2026-09-01',
        'endDate': '2026-09-30',
      });

      expect(budget.id, 7);
      expect(budget.categoryId, 10);
      expect(budget.categoryName, 'Ăn uống');
      expect(budget.amount, 2000000.0);
      expect(budget.periodType, BudgetPeriodType.month);
      expect(budget.startDate, '2026-09-01');
      expect(budget.endDate, '2026-09-30');
      expect(budget.start, DateTime(2026, 9, 1));
      expect(budget.end, DateTime(2026, 9, 30));
    });

    test(
      'fromJson reads a budget for every category and an integer amount',
      () {
        final budget = BudgetModel.fromJson({
          'id': 8,
          'categoryId': null,
          'categoryName': null,
          'amount': 500000,
          'periodType': 'WEEK',
          'startDate': '2026-09-14',
          'endDate': '2026-09-20',
        });

        expect(budget.categoryId, isNull);
        expect(budget.categoryName, isNull);
        expect(budget.amount, 500000.0);
        expect(budget.periodType, BudgetPeriodType.week);
      },
    );

    test(
      'fromJson refuses a response without its dates instead of inventing them',
      () {
        expect(
          () => BudgetModel.fromJson({
            'id': 1,
            'amount': 1,
            'periodType': 'MONTH',
          }),
          throwsA(isA<TypeError>()),
        );
      },
    );

    test('toJson is exactly the request body: no id, no user', () {
      const budget = BudgetModel(
        id: 7,
        categoryId: null,
        amount: 1500000,
        periodType: BudgetPeriodType.week,
        startDate: '2026-09-14',
        endDate: '2026-09-20',
        userId: 3,
      );

      expect(budget.toJson(), {
        'categoryId': null,
        'amount': 1500000.0,
        'periodType': 'WEEK',
        'startDate': '2026-09-14',
        'endDate': '2026-09-20',
      });
    });

    test('forPeriod computes both dates from the period type', () {
      final week = BudgetModel.forPeriod(
        amount: 1,
        periodType: BudgetPeriodType.week,
        day: DateTime(2026, 9, 16),
      );
      expect((week.startDate, week.endDate), ('2026-09-14', '2026-09-20'));

      final month = BudgetModel.forPeriod(
        amount: 1,
        periodType: BudgetPeriodType.month,
        day: DateTime(2026, 2, 10),
      );
      expect((month.startDate, month.endDate), ('2026-02-01', '2026-02-28'));
    });

    test('formatApiDate pads month and day', () {
      expect(BudgetModel.formatApiDate(DateTime(2026, 1, 5)), '2026-01-05');
    });

    test('copyWith can clear the category', () {
      const budget = BudgetModel(
        categoryId: 10,
        categoryName: 'Ăn uống',
        amount: 1,
        periodType: BudgetPeriodType.month,
        startDate: '2026-09-01',
        endDate: '2026-09-30',
      );

      final cleared = budget.copyWith(clearCategory: true);

      expect(cleared.categoryId, isNull);
      expect(cleared.categoryName, isNull);
      expect(budget.copyWith(amount: 2).categoryId, 10);
    });
  });

  group('BudgetModel and the local SQLite table (offline mode)', () {
    test('fromMap reads a row of the existing table as a monthly budget', () {
      final budget = BudgetModel.fromMap({
        'id': 1,
        'category_id': 3,
        'category_name': 'Ăn uống',
        'limit_amount': 500000.0,
        'month': 9,
        'year': 2026,
        'user_id': 7,
      });

      expect(budget.id, 1);
      expect(budget.categoryId, 3);
      expect(budget.categoryName, 'Ăn uống');
      expect(budget.amount, 500000.0);
      expect(budget.periodType, BudgetPeriodType.month);
      expect((budget.startDate, budget.endDate), ('2026-09-01', '2026-09-30'));
      expect(budget.userId, 7);
    });

    test(
      'a budget for every category is stored under category id 0 and read back as null',
      () {
        final row = BudgetModel.forPeriod(
          amount: 1000000,
          periodType: BudgetPeriodType.month,
          day: DateTime(2026, 9, 16),
          userId: 7,
        ).toMap();

        expect(row['category_id'], BudgetModel.allCategoriesLocalId);
        expect(row['category_name'], '');

        final back = BudgetModel.fromMap(row);
        expect(back.categoryId, isNull);
        expect(back.categoryName, isNull);
      },
    );

    test('toMap keeps the column names and the month of the budget', () {
      const budget = BudgetModel(
        id: 5,
        categoryId: 3,
        categoryName: 'Ăn uống',
        amount: 750000,
        periodType: BudgetPeriodType.month,
        startDate: '2026-09-01',
        endDate: '2026-09-30',
        userId: 7,
      );

      expect(budget.toMap(), {
        'id': 5,
        'category_id': 3,
        'category_name': 'Ăn uống',
        'limit_amount': 750000.0,
        'month': 9,
        'year': 2026,
        'user_id': 7,
      });
    });
  });

  group('BudgetModel period membership', () {
    const september = BudgetModel(
      amount: 1,
      periodType: BudgetPeriodType.month,
      startDate: '2026-09-01',
      endDate: '2026-09-30',
    );

    test('coversDay includes both end days, whatever the time of day', () {
      expect(september.coversDay(DateTime(2026, 9, 1, 0, 0, 0)), isTrue);
      expect(september.coversDay(DateTime(2026, 9, 30, 23, 59, 59)), isTrue);
      expect(september.coversDay(DateTime(2026, 8, 31, 23, 59, 59)), isFalse);
      expect(september.coversDay(DateTime(2026, 10, 1)), isFalse);
    });

    test('overlaps is true as soon as one day is shared', () {
      expect(
        september.overlaps(DateTime(2026, 8, 25), DateTime(2026, 9, 1)),
        isTrue,
      );
      expect(
        september.overlaps(DateTime(2026, 9, 30), DateTime(2026, 10, 5)),
        isTrue,
      );
      expect(
        september.overlaps(DateTime(2026, 9, 10), DateTime(2026, 9, 12)),
        isTrue,
      );
      expect(
        september.overlaps(DateTime(2026, 8, 1), DateTime(2026, 12, 31)),
        isTrue,
      );
      expect(
        september.overlaps(DateTime(2026, 8, 1), DateTime(2026, 8, 31)),
        isFalse,
      );
      expect(
        september.overlaps(DateTime(2026, 10, 1), DateTime(2026, 10, 31)),
        isFalse,
      );
    });
  });
}
