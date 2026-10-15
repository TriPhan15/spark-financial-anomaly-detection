import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/models/spending_range.dart';

/// A Wednesday: its week runs from Monday 21 to Sunday 27 September 2026 and its month from the 1st to the 30th.
final DateTime _wednesday = DateTime(2026, 9, 23, 14, 30);

void main() {
  group('SpendingRange.week', () {
    test(
      'runs from the Monday to the Sunday around the given day, both at midnight',
      () {
        final range = SpendingRange.week(_wednesday);

        expect(range.period, SpendingPeriod.week);
        expect(range.start, DateTime(2026, 9, 21));
        expect(range.end, DateTime(2026, 9, 27));
        expect(range.days, 7);
        expect(range.label, '21/09/2026 - 27/09/2026');
      },
    );

    test(
      'a Monday and a Sunday belong to their own week, not to a neighbouring one',
      () {
        expect(
          SpendingRange.week(DateTime(2026, 9, 21)).start,
          DateTime(2026, 9, 21),
        );
        expect(
          SpendingRange.week(DateTime(2026, 9, 27, 23, 59)).start,
          DateTime(2026, 9, 21),
        );
        expect(
          SpendingRange.week(DateTime(2026, 9, 28)).start,
          DateTime(2026, 9, 28),
        );
      },
    );

    test('a week can begin in the month before', () {
      final range = SpendingRange.week(DateTime(2026, 10, 1));

      expect(range.start, DateTime(2026, 9, 28));
      expect(range.end, DateTime(2026, 10, 4));
    });
  });

  group('SpendingRange.month', () {
    test('runs from the first to the last day of the month', () {
      final range = SpendingRange.month(_wednesday);

      expect(range.period, SpendingPeriod.month);
      expect(range.start, DateTime(2026, 9, 1));
      expect(range.end, DateTime(2026, 9, 30));
      expect(range.days, 30);
      expect(range.label, '01/09/2026 - 30/09/2026');
    });

    test('knows how long February and December are', () {
      expect(
        SpendingRange.month(DateTime(2028, 2, 10)).end,
        DateTime(2028, 2, 29),
      );
      expect(
        SpendingRange.month(DateTime(2027, 2, 10)).end,
        DateTime(2027, 2, 28),
      );
      expect(
        SpendingRange.month(DateTime(2026, 12, 31)).end,
        DateTime(2026, 12, 31),
      );
    });
  });

  group('SpendingRange.custom', () {
    test('takes the days as they are and drops the time of day', () {
      final range = SpendingRange.custom(
        DateTime(2026, 9, 20, 7, 15),
        DateTime(2026, 9, 25, 18, 30),
      );

      expect(range.period, SpendingPeriod.custom);
      expect(range.start, DateTime(2026, 9, 20));
      expect(range.end, DateTime(2026, 9, 25));
      expect(range.days, 6);
    });

    test('puts the two days in order when they come reversed', () {
      final range = SpendingRange.custom(
        DateTime(2026, 9, 25),
        DateTime(2026, 9, 20),
      );

      expect(range.start, DateTime(2026, 9, 20));
      expect(range.end, DateTime(2026, 9, 25));
    });

    test('one day is a range of one day', () {
      final range = SpendingRange.custom(
        DateTime(2026, 9, 20, 8),
        DateTime(2026, 9, 20, 22),
      );

      expect(range.days, 1);
      expect(range.label, '20/09/2026 - 20/09/2026');
    });

    test('counts every day of a range that crosses a year end', () {
      expect(
        SpendingRange.custom(DateTime(2026, 12, 30), DateTime(2027, 1, 2)).days,
        4,
      );
    });
  });

  group('SpendingRange.of', () {
    test('gives the week or the month as of the given day', () {
      expect(
        SpendingRange.of(SpendingPeriod.week, _wednesday),
        SpendingRange.week(_wednesday),
      );
      expect(
        SpendingRange.of(SpendingPeriod.month, _wednesday),
        SpendingRange.month(_wednesday),
      );
    });

    test(
      'a custom period uses the range the user picked, and the month while none is picked',
      () {
        final picked = SpendingRange.custom(
          DateTime(2026, 8, 1),
          DateTime(2026, 8, 15),
        );

        expect(
          SpendingRange.of(SpendingPeriod.custom, _wednesday, custom: picked),
          picked,
        );
        expect(
          SpendingRange.of(SpendingPeriod.custom, _wednesday),
          SpendingRange.month(_wednesday),
        );
      },
    );

    test('follows the clock: the month of another day is another range', () {
      expect(
        SpendingRange.of(SpendingPeriod.month, DateTime(2026, 10, 2)).start,
        DateTime(2026, 10, 1),
      );
    });
  });

  group('equality', () {
    test('ranges of the same period over the same days are equal', () {
      expect(
        SpendingRange.week(_wednesday),
        SpendingRange.week(DateTime(2026, 9, 25)),
      );
      expect(
        SpendingRange.week(_wednesday).hashCode,
        SpendingRange.week(DateTime(2026, 9, 25)).hashCode,
      );
    });

    test(
      'a week and a custom range over the same days are different ranges',
      () {
        final week = SpendingRange.week(_wednesday);

        expect(SpendingRange.custom(week.start, week.end), isNot(week));
      },
    );

    test('other days are other ranges', () {
      expect(
        SpendingRange.week(_wednesday),
        isNot(SpendingRange.week(DateTime(2026, 9, 30))),
      );
    });
  });

  group('SpendingRange.contains', () {
    final september = SpendingRange.month(_wednesday);

    test('includes the first and the last day at any time of day', () {
      expect(september.contains(DateTime(2026, 9, 1)), isTrue);
      expect(september.contains(DateTime(2026, 9, 1, 0, 0, 1)), isTrue);
      expect(september.contains(DateTime(2026, 9, 30, 23, 59, 59)), isTrue);
    });

    test('leaves out the day before and the day after', () {
      expect(september.contains(DateTime(2026, 8, 31, 23, 59, 59)), isFalse);
      expect(september.contains(DateTime(2026, 10, 1)), isFalse);
    });
  });

  group('SpendingBucket.contains', () {
    final bucket = SpendingBucket(
      label: 'W2',
      start: DateTime(2026, 9, 8),
      end: DateTime(2026, 9, 15),
    );

    test(
      'includes the days from the start up to the day before the end, at any time of day',
      () {
        expect(bucket.contains(DateTime(2026, 9, 8)), isTrue);
        expect(bucket.contains(DateTime(2026, 9, 14, 23, 59, 59)), isTrue);
      },
    );

    test('leaves out the day before the start and the end day itself', () {
      expect(bucket.contains(DateTime(2026, 9, 7, 23, 59, 59)), isFalse);
      expect(bucket.contains(DateTime(2026, 9, 15)), isFalse);
    });
  });

  group('buckets of a month', () {
    test(
      'are the four weeks of the chart, the last one taking the rest of the month',
      () {
        final buckets = SpendingRange.month(DateTime(2026, 9, 10)).buckets;

        expect(buckets.map((b) => b.label), ['W1', 'W2', 'W3', 'W4']);
        expect(buckets.map((b) => b.start), [
          DateTime(2026, 9, 1),
          DateTime(2026, 9, 8),
          DateTime(2026, 9, 15),
          DateTime(2026, 9, 22),
        ]);
        expect(buckets.last.end, DateTime(2026, 10, 1));
      },
    );

    test(
      'put every day in the bar the chart always used: 1-7, 8-14, 15-21 and 22 onwards',
      () {
        final buckets = SpendingRange.month(DateTime(2026, 9, 10)).buckets;

        for (var day = 1; day <= 30; day++) {
          final expected = day <= 7 ? 0 : (day <= 14 ? 1 : (day <= 21 ? 2 : 3));
          for (final time in [
            DateTime(2026, 9, day),
            DateTime(2026, 9, day, 23, 59),
          ]) {
            expect(
              buckets.indexWhere((b) => b.contains(time)),
              expected,
              reason: '$time',
            );
          }
        }
      },
    );

    test('are still four for the shortest and the longest month', () {
      final february = SpendingRange.month(DateTime(2027, 2, 1)).buckets;
      final december = SpendingRange.month(DateTime(2026, 12, 1)).buckets;

      expect(february.length, 4);
      expect(february.last.start, DateTime(2027, 2, 22));
      expect(february.last.end, DateTime(2027, 3, 1));
      expect(december.length, 4);
      expect(december.last.start, DateTime(2026, 12, 22));
      expect(december.last.end, DateTime(2027, 1, 1));
    });
  });

  group('buckets of a week', () {
    test('are its seven days, each named after its weekday', () {
      final buckets = SpendingRange.week(_wednesday).buckets;

      expect(buckets.map((b) => b.label), [
        'T2',
        'T3',
        'T4',
        'T5',
        'T6',
        'T7',
        'CN',
      ]);
      expect(buckets.first.start, DateTime(2026, 9, 21));
      expect(buckets.first.end, DateTime(2026, 9, 22));
      expect(buckets.last.start, DateTime(2026, 9, 27));
      expect(buckets.last.end, DateTime(2026, 9, 28));
    });
  });

  group('buckets of a custom range', () {
    test(
      'a range of up to a week has one bar per day, named after the weekday',
      () {
        final buckets = SpendingRange.custom(
          DateTime(2026, 9, 23),
          DateTime(2026, 9, 25),
        ).buckets;

        expect(buckets.map((b) => b.label), ['T4', 'T5', 'T6']);
      },
    );

    test(
      'a longer range is cut into bars of as few days as keep it within seven bars, named after their first day',
      () {
        final buckets = SpendingRange.custom(
          DateTime(2026, 9, 1),
          DateTime(2026, 9, 10),
        ).buckets;

        expect(buckets.map((b) => b.label), [
          '01/09',
          '03/09',
          '05/09',
          '07/09',
          '09/09',
        ]);
        expect(buckets.last.end, DateTime(2026, 9, 11));
      },
    );

    test(
      'the last bar is shorter when the days do not divide evenly, and ends with the range',
      () {
        final buckets = SpendingRange.custom(
          DateTime(2026, 9, 1),
          DateTime(2026, 9, 9),
        ).buckets;

        expect(buckets.map((b) => b.label), [
          '01/09',
          '03/09',
          '05/09',
          '07/09',
          '09/09',
        ]);
        expect(buckets.last.start, DateTime(2026, 9, 9));
        expect(buckets.last.end, DateTime(2026, 9, 10));
      },
    );

    test('a year is still seven bars', () {
      final buckets = SpendingRange.custom(
        DateTime(2026, 1, 1),
        DateTime(2026, 12, 31),
      ).buckets;

      expect(buckets.length, 7);
    });

    test(
      'for any length the bars follow one another, cover exactly the days of the range, and are at most seven',
      () {
        for (var days = 1; days <= 400; days++) {
          final range = SpendingRange.custom(
            DateTime(2026, 1, 1),
            DateTime(2026, 1, days),
          );
          final buckets = range.buckets;
          final reason = '$days days';

          expect(range.days, days, reason: reason);
          expect(
            buckets.length,
            lessThanOrEqualTo(SpendingRange.maxFittedBuckets),
            reason: reason,
          );
          expect(buckets.first.start, range.start, reason: reason);
          expect(
            buckets.last.end,
            DateTime(range.end.year, range.end.month, range.end.day + 1),
            reason: reason,
          );
          for (var i = 0; i < buckets.length; i++) {
            expect(
              buckets[i].end.isAfter(buckets[i].start),
              isTrue,
              reason: '$reason, bar $i is empty',
            );
            if (i > 0) {
              expect(
                buckets[i].start,
                buckets[i - 1].end,
                reason: '$reason, gap before bar $i',
              );
            }
          }
        }
      },
    );
  });
}
