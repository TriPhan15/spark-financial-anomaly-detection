import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/models/budget_model.dart';
import 'package:finance_ai_app/models/budget_progress.dart';
import 'package:finance_ai_app/services/budget_alert_tracker.dart';

const int _user = 1;

BudgetProgress _progress(
  double spent, {
  int id = 1,
  double limit = 1000000,
  String start = '2026-09-01',
  String end = '2026-09-30',
}) => BudgetProgress(
  budget: BudgetModel(
    id: id,
    categoryId: 10,
    categoryName: 'Ăn uống',
    amount: limit,
    periodType: BudgetPeriodType.month,
    startDate: start,
    endDate: end,
  ),
  spent: spent,
);

Future<BudgetAlertTracker> _tracker() async {
  final tracker = BudgetAlertTracker();
  await tracker.ensureLoaded();
  return tracker;
}

List<BudgetAlertLevel> _levels(List<BudgetAlert> alerts) =>
    alerts.map((a) => a.level).toList();

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  test('nothing is announced below 80%', () async {
    final tracker = await _tracker();

    expect(tracker.pending(_user, [_progress(0), _progress(799999)]), isEmpty);
  });

  test(
    '80% is announced once: checking again after marking it shown says nothing',
    () async {
      final tracker = await _tracker();
      final progress = _progress(850000);

      final first = tracker.pending(_user, [progress]);
      expect(_levels(first), [BudgetAlertLevel.warning]);
      expect(
        _levels(tracker.pending(_user, [progress])),
        [BudgetAlertLevel.warning],
        reason:
            'looking is not the same as showing: only markShown silences an alert',
      );

      await tracker.markShown(_user, first);

      expect(tracker.pending(_user, [progress]), isEmpty);
      expect(
        tracker.pending(_user, [_progress(890000)]),
        isEmpty,
        reason: 'more spending, same level',
      );
    },
  );

  test(
    '100% is announced as its own step after the warning, and only once',
    () async {
      final tracker = await _tracker();
      await tracker.markShown(
        _user,
        tracker.pending(_user, [_progress(850000)]),
      );

      final exceeded = tracker.pending(_user, [_progress(1000000)]);
      expect(_levels(exceeded), [BudgetAlertLevel.exceeded]);

      await tracker.markShown(_user, exceeded);
      expect(tracker.pending(_user, [_progress(1300000)]), isEmpty);
    },
  );

  test(
    'a jump from 70% straight to 110% announces "exceeded" only, and never the warning afterwards',
    () async {
      final tracker = await _tracker();
      expect(tracker.pending(_user, [_progress(700000)]), isEmpty);

      final jump = tracker.pending(_user, [_progress(1100000)]);
      expect(_levels(jump), [BudgetAlertLevel.exceeded]);
      await tracker.markShown(_user, jump);

      // Some spending is deleted and the budget falls back to 85%: that is not news.
      expect(tracker.pending(_user, [_progress(850000)]), isEmpty);
    },
  );

  test('budgets are independent of each other', () async {
    final tracker = await _tracker();
    final alerts = tracker.pending(_user, [
      _progress(900000, id: 1),
      _progress(100000, id: 2),
    ]);
    await tracker.markShown(_user, alerts);

    expect(alerts.map((a) => a.progress.budget.id), [1]);
    expect(_levels(tracker.pending(_user, [_progress(900000, id: 2)])), [
      BudgetAlertLevel.warning,
    ]);
  });

  test('users do not share flags on the same device', () async {
    final tracker = await _tracker();
    await tracker.markShown(1, tracker.pending(1, [_progress(900000)]));

    expect(tracker.pending(1, [_progress(900000)]), isEmpty);
    expect(_levels(tracker.pending(2, [_progress(900000)])), [
      BudgetAlertLevel.warning,
    ]);
  });

  test('a new period or a changed limit can announce again', () async {
    final tracker = await _tracker();
    await tracker.markShown(_user, tracker.pending(_user, [_progress(900000)]));

    expect(
      _levels(
        tracker.pending(_user, [
          _progress(900000, start: '2026-10-01', end: '2026-10-31'),
        ]),
      ),
      [BudgetAlertLevel.warning],
    );
    // The limit was raised to 1,000,000 -> 1,050,000 and 90% of it is spent again.
    expect(
      _levels(tracker.pending(_user, [_progress(945000, limit: 1050000)])),
      [BudgetAlertLevel.warning],
    );
  });

  test(
    'a flag set a moment ago is already seen, before the write to disk has finished',
    () async {
      final tracker = await _tracker();
      final alerts = tracker.pending(_user, [_progress(900000)]);

      final saving = tracker.markShown(
        _user,
        alerts,
      ); // deliberately not awaited
      expect(tracker.pending(_user, [_progress(900000)]), isEmpty);
      await saving;
    },
  );

  test('flags survive a restart of the app', () async {
    final first = await _tracker();
    await first.markShown(_user, first.pending(_user, [_progress(900000)]));

    final afterRestart = await _tracker();

    expect(afterRestart.pending(_user, [_progress(900000)]), isEmpty);
  });

  test(
    'two callers that load at the same time read the stored flags only once and agree',
    () async {
      SharedPreferences.setMockInitialValues({
        'budget_alerts_shown': ['1|1|2026-09-01|2026-09-30|1000000.0|warning'],
      });
      final tracker = BudgetAlertTracker();

      await Future.wait([tracker.ensureLoaded(), tracker.ensureLoaded()]);

      expect(tracker.pending(_user, [_progress(900000)]), isEmpty);
    },
  );

  test(
    'flags of periods that ended long ago are forgotten, recent ones are kept',
    () async {
      final tracker = await _tracker();
      final old = _progress(
        900000,
        id: 1,
        start: '2026-05-01',
        end: '2026-05-31',
      );
      final recent = _progress(
        900000,
        id: 2,
        start: '2026-09-01',
        end: '2026-09-30',
      );
      final now = DateTime(2026, 9, 20);

      await tracker.markShown(
        _user,
        tracker.pending(_user, [old, recent]),
        now: now,
      );

      final stored = (await SharedPreferences.getInstance()).getStringList(
        'budget_alerts_shown',
      )!;
      expect(stored.any((flag) => flag.contains('2026-05-31')), isFalse);
      expect(stored.any((flag) => flag.contains('2026-09-30')), isTrue);
    },
  );

  test(
    'malformed stored flags are dropped the next time flags are saved',
    () async {
      SharedPreferences.setMockInitialValues({
        'budget_alerts_shown': [
          'not-a-flag',
          '1|1|not-a-date|not-a-date|1000000.0|warning',
        ],
      });
      final tracker = BudgetAlertTracker();
      await tracker.ensureLoaded();

      await tracker.markShown(
        _user,
        tracker.pending(_user, [_progress(900000)]),
        now: DateTime(2026, 9, 20),
      );

      final stored = (await SharedPreferences.getInstance()).getStringList(
        'budget_alerts_shown',
      );
      expect(stored, ['1|1|2026-09-01|2026-09-30|1000000.0|warning']);
    },
  );

  test('unreadable stored data does not break the alerts', () async {
    SharedPreferences.setMockInitialValues({'budget_alerts_shown': 5});
    final tracker = BudgetAlertTracker();

    await tracker.ensureLoaded();

    expect(_levels(tracker.pending(_user, [_progress(900000)])), [
      BudgetAlertLevel.warning,
    ]);
  });
}
