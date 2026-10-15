import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/models/budget_model.dart';
import 'package:finance_ai_app/models/budget_progress.dart';
import 'package:finance_ai_app/services/budget_alert_tracker.dart';
import 'package:finance_ai_app/widgets/budget/budget_alert_presenter.dart';

import '../../helpers/captured_logs.dart';

const int _user = 1;

BudgetProgress _progress(
  double spent, {
  int id = 1,
  String name = 'Ăn uống',
  String periodType = BudgetPeriodType.month,
  double limit = 1000000,
}) => BudgetProgress(
  budget: BudgetModel(
    id: id,
    categoryId: id,
    categoryName: name,
    amount: limit,
    periodType: periodType,
    startDate: '2026-09-01',
    endDate: '2026-09-30',
  ),
  spent: spent,
);

/// A tracker that waits for [gate] before it reads its stored flags.
class _SlowTracker extends BudgetAlertTracker {
  final gate = Completer<void>();

  @override
  Future<void> ensureLoaded() async {
    await gate.future;
    await super.ensureLoaded();
  }
}

class _BrokenTracker extends BudgetAlertTracker {
  @override
  List<BudgetAlert> pending(int userId, Iterable<BudgetProgress> progresses) =>
      throw StateError('broken');
}

/// A screen with a button that asks for alerts, the way a page does when it (re)loads.
Widget _host(void Function(BuildContext context) onCheck) => MaterialApp(
  home: Builder(
    builder: (context) => Scaffold(
      body: Center(
        child: ElevatedButton(
          onPressed: () => onCheck(context),
          child: const Text('check'),
        ),
      ),
    ),
  ),
);

Future<void> _check(WidgetTester tester) async {
  await tester.tap(find.text('check'));
  await tester.pumpAndSettle();
}

Future<void> _dismiss(WidgetTester tester) async {
  await tester.tap(find.text('Đã hiểu'));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    BudgetAlertTracker.instance.resetMemory();
    BudgetAlertPresenter.resetQueue();
  });

  testWidgets('shows nothing while every budget is below 80%', (tester) async {
    await tester.pumpWidget(
      _host(
        (context) => BudgetAlertPresenter.showNew(
          context,
          userId: _user,
          progresses: [_progress(0), _progress(799999, id: 2)],
        ),
      ),
    );

    await _check(tester);

    expect(find.byType(Dialog), findsNothing);
  });

  testWidgets(
    '80% opens one warning with the numbers, and asking again does not repeat it',
    (tester) async {
      await tester.pumpWidget(
        _host(
          (context) => BudgetAlertPresenter.showNew(
            context,
            userId: _user,
            progresses: [_progress(850000)],
          ),
        ),
      );

      await _check(tester);
      expect(find.text('Sắp chạm hạn mức ngân sách'), findsOneWidget);
      expect(
        find.textContaining('Ăn uống (theo tháng): đã dùng 85% hạn mức'),
        findsOneWidget,
      );
      expect(find.byIcon(Icons.warning_amber_rounded), findsOneWidget);
      expect(
        BudgetAlertTracker.instance.pending(_user, [_progress(850000)]),
        isEmpty,
        reason:
            'the alert counts as shown from the moment it is on screen, not only once it is dismissed',
      );
      await _dismiss(tester);

      await _check(tester);
      await _check(tester);
      expect(find.byType(Dialog), findsNothing);
    },
  );

  testWidgets('100% opens an error once', (tester) async {
    await tester.pumpWidget(
      _host(
        (context) => BudgetAlertPresenter.showNew(
          context,
          userId: _user,
          progresses: [_progress(1150000)],
        ),
      ),
    );

    await _check(tester);
    expect(find.text('Đã vượt ngân sách'), findsOneWidget);
    expect(find.textContaining('đã vượt hạn mức'), findsOneWidget);
    expect(find.textContaining('(115%)'), findsOneWidget);
    expect(find.byIcon(Icons.error_rounded), findsOneWidget);
    await _dismiss(tester);

    await _check(tester);
    expect(find.byType(Dialog), findsNothing);
  });

  testWidgets('the warning and, later, the excess are each announced once', (
    tester,
  ) async {
    var spent = 850000.0;
    await tester.pumpWidget(
      _host(
        (context) => BudgetAlertPresenter.showNew(
          context,
          userId: _user,
          progresses: [_progress(spent)],
        ),
      ),
    );

    await _check(tester);
    expect(find.text('Sắp chạm hạn mức ngân sách'), findsOneWidget);
    await _dismiss(tester);

    spent = 1000000;
    await _check(tester);
    expect(find.text('Đã vượt ngân sách'), findsOneWidget);
    await _dismiss(tester);

    spent = 1400000;
    await _check(tester);
    spent = 850000;
    await _check(tester);
    expect(find.byType(Dialog), findsNothing);
  });

  testWidgets(
    'leaving and coming back to the screen (a tab switch) does not announce the same alerts again',
    (tester) async {
      Widget screen() => _host(
        (context) => BudgetAlertPresenter.showNew(
          context,
          userId: _user,
          progresses: [_progress(900000)],
        ),
      );

      await tester.pumpWidget(screen());
      await _check(tester);
      await _dismiss(tester);

      await tester.pumpWidget(
        const MaterialApp(home: SizedBox()),
      ); // another tab
      await tester.pumpWidget(
        screen(),
      ); // back on the first one: a brand new State
      await _check(tester);

      expect(find.byType(Dialog), findsNothing);
    },
  );

  testWidgets(
    'several budgets at once share one dialog, the exceeded ones first',
    (tester) async {
      await tester.pumpWidget(
        _host(
          (context) => BudgetAlertPresenter.showNew(
            context,
            userId: _user,
            progresses: [
              _progress(850000, id: 1, name: 'Di chuyển'),
              _progress(
                1200000,
                id: 2,
                name: 'Ăn uống',
                periodType: BudgetPeriodType.week,
              ),
            ],
          ),
        ),
      );

      await _check(tester);

      expect(find.byType(Dialog), findsOneWidget);
      expect(find.text('Đã vượt ngân sách'), findsOneWidget);
      final lines = tester
          .widget<Text>(find.textContaining('Ăn uống (theo tuần)'))
          .data!
          .split('\n');
      expect(lines.length, 2);
      expect(lines.first, contains('Ăn uống (theo tuần): đã vượt hạn mức'));
      expect(
        lines.last,
        contains('Di chuyển (theo tháng): đã dùng 85% hạn mức'),
      );
    },
  );

  testWidgets(
    'two screens that load at the same moment (home and ledger) open one dialog between them',
    (tester) async {
      await tester.pumpWidget(
        _host((context) {
          final progresses = [_progress(900000)];
          unawaited(
            BudgetAlertPresenter.showNew(
              context,
              userId: _user,
              progresses: progresses,
            ),
          ); // the home screen
          unawaited(
            BudgetAlertPresenter.showNew(
              context,
              userId: _user,
              progresses: progresses,
            ),
          ); // the ledger
        }),
      );

      await _check(tester);
      expect(find.byType(Dialog), findsOneWidget);
      await _dismiss(tester);

      expect(find.byType(Dialog), findsNothing);
    },
  );

  testWidgets(
    'an alert that arrives while a dialog is still open waits its turn instead of piling on top of it',
    (tester) async {
      late BuildContext screen;
      await tester.pumpWidget(
        _host((context) {
          screen = context;
          BudgetAlertPresenter.showNew(
            context,
            userId: _user,
            progresses: [_progress(850000)],
          );
        }),
      );
      await _check(tester);
      expect(find.text('Sắp chạm hạn mức ngân sách'), findsOneWidget);

      unawaited(
        BudgetAlertPresenter.showNew(
          screen,
          userId: _user,
          progresses: [_progress(1100000)],
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(Dialog), findsOneWidget);
      expect(
        find.text('Đã vượt ngân sách'),
        findsNothing,
        reason: 'the first dialog is still waiting for the user',
      );

      await _dismiss(tester);
      expect(find.byType(Dialog), findsOneWidget);
      expect(find.text('Đã vượt ngân sách'), findsOneWidget);
      await _dismiss(tester);
      expect(find.byType(Dialog), findsNothing);
    },
  );

  testWidgets('after a restart of the app the alerts already seen stay quiet', (
    tester,
  ) async {
    await tester.pumpWidget(
      _host(
        (context) => BudgetAlertPresenter.showNew(
          context,
          userId: _user,
          progresses: [_progress(900000)],
        ),
      ),
    );
    await _check(tester);
    await _dismiss(tester);

    BudgetAlertTracker.instance
        .resetMemory(); // the process ends; SharedPreferences stays
    BudgetAlertPresenter.resetQueue();
    await _check(tester);

    expect(find.byType(Dialog), findsNothing);
  });

  testWidgets(
    'a screen that is gone by the time the flags are read shows nothing, and the alert is kept for later',
    (tester) async {
      final tracker = _SlowTracker();
      await tester.pumpWidget(
        _host(
          (context) => unawaited(
            BudgetAlertPresenter.showNew(
              context,
              userId: _user,
              progresses: [_progress(900000)],
              tracker: tracker,
            ),
          ),
        ),
      );
      await tester.tap(find.text('check'));
      await tester.pump();

      await tester.pumpWidget(const MaterialApp(home: SizedBox()));
      tracker.gate.complete();
      await tester.pumpAndSettle();
      expect(find.byType(Dialog), findsNothing);
      expect(tester.takeException(), isNull);

      await tester.pumpWidget(
        _host(
          (context) => unawaited(
            BudgetAlertPresenter.showNew(
              context,
              userId: _user,
              progresses: [_progress(900000)],
              tracker: tracker,
            ),
          ),
        ),
      );
      await _check(tester);
      expect(find.text('Sắp chạm hạn mức ngân sách'), findsOneWidget);
    },
  );

  testWidgets(
    'a failure inside never escapes, is logged, and does not block later alerts',
    (tester) async {
      await withCapturedLogs((logs) async {
        Object? escaped;
        await tester.pumpWidget(
          _host((context) async {
            try {
              await BudgetAlertPresenter.showNew(
                context,
                userId: _user,
                progresses: [_progress(900000)],
                tracker: _BrokenTracker(),
              );
            } catch (e) {
              escaped = e;
            }
          }),
        );
        await _check(tester);

        expect(escaped, isNull);
        expect(logs, [contains('broken')]);
      });

      await tester.pumpWidget(
        _host(
          (context) => BudgetAlertPresenter.showNew(
            context,
            userId: _user,
            progresses: [_progress(900000)],
          ),
        ),
      );
      await _check(tester);
      expect(find.text('Sắp chạm hạn mức ngân sách'), findsOneWidget);
    },
  );
}
