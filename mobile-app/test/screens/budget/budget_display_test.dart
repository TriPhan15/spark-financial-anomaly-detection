import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/l10n/app_localizations.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/budget/budget_screen.dart';
import 'package:finance_ai_app/screens/dashboard/dashboard_page.dart';
import 'package:finance_ai_app/screens/ledger/ledger_page.dart';
import 'package:finance_ai_app/services/budget_alert_tracker.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/widgets/budget/budget_alert_presenter.dart';
import 'package:finance_ai_app/widgets/budget/budget_progress_card.dart';

import '../../helpers/captured_logs.dart';
import '../../helpers/fake_backend.dart';
import '../../helpers/fake_local_store.dart';

const String _budgetsCall = 'GET /api/budgets';
const String _warningTitle = 'Sắp chạm hạn mức ngân sách';
const String _exceededTitle = 'Đã vượt ngân sách';
const String _loadFailedNote =
    'Không tải được ngân sách. Kéo xuống để thử lại.';
const String _emptyPeriodNote = 'Chưa có ngân sách trong kỳ này.';

final NumberFormat _money = NumberFormat.currency(
  locale: 'vi_VN',
  symbol: 'đ',
  decimalDigits: 0,
);
final DateFormat _day = DateFormat('dd/MM/yyyy');

String _ymd(DateTime d) => DateFormat('yyyy-MM-dd').format(d);

/// The dates are built from today, because both screens ask "which budgets cover today?".
DateTime _today() {
  final now = DateTime.now();
  return DateTime(now.year, now.month, now.day);
}

DateTime _monthStart() => DateTime(_today().year, _today().month, 1);

DateTime _monthEnd() => DateTime(_today().year, _today().month + 1, 0);

DateTime _weekStart() => DateTime(
  _today().year,
  _today().month,
  _today().day - (_today().weekday - 1),
);

Map<String, Object?> _budget({
  int id = 1,
  String? category = 'Ăn uống',
  num amount = 1000000,
  String periodType = 'MONTH',
  DateTime? start,
  DateTime? end,
}) => {
  'id': id,
  'categoryId': category == null ? null : 10,
  'categoryName': category,
  'amount': amount,
  'periodType': periodType,
  'startDate': _ymd(start ?? _monthStart()),
  'endDate': _ymd(end ?? _monthEnd()),
};

Map<String, Object?> _tx(
  num amount, {
  int id = 1,
  String category = 'Ăn uống',
  bool isExpense = true,
  DateTime? date,
}) => {
  'id': id,
  'title': 'Giao dịch $id',
  'amount': amount,
  'date': (date ?? DateTime.now()).toIso8601String(),
  'category': category,
  'isExpense': isExpense,
};

/// The home screen and the ledger side by side in an IndexedStack, both alive from the start, the way MainScaffold
/// keeps them.
class _Tabs {
  final ValueNotifier<int> index = ValueNotifier<int>(0);
  final GlobalKey<DashboardPageState> dashboard =
      GlobalKey<DashboardPageState>();
  final GlobalKey<LedgerPageState> ledger = GlobalKey<LedgerPageState>();

  Widget get app => MaterialApp(
    // The bell of the home screen reads its tooltip from the app's localizations.
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: Scaffold(
      body: ValueListenableBuilder<int>(
        valueListenable: index,
        builder: (_, current, _) => IndexedStack(
          index: current,
          children: [
            DashboardPage(key: dashboard),
            LedgerPage(key: ledger),
          ],
        ),
      ),
    ),
  );
}

void main() {
  final backend = FakeBackend();
  var transactions = <Map<String, Object?>>[];
  var budgets = <Map<String, Object?>>[];

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
    // The lists also read the transactions waiting to be sent; the fake keeps the test away from a real database.
    AppRepository.instance.localStore = FakeLocalStore();
  });

  setUp(() async {
    // SharedPreferences shares one Future. It must be created inside the test body, which runs in fake async: a Future
    // created here and awaited there never wakes up. So the store is reset before the session is written (setUp awaits
    // it) and again after, and the body starts from scratch.
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    SharedPreferences.setMockInitialValues({});
    BudgetAlertTracker.instance.resetMemory();
    BudgetAlertPresenter.resetQueue();

    backend.reset();
    transactions = [];
    budgets = [];
    backend.on(
      'GET',
      '/api/users/me',
      (_) => okResponse({'id': 1, 'email': 'an@example.com'}),
    );
    backend.on(
      'GET',
      '/api/transactions/mine',
      (_) => okResponse(transactions),
    );
    backend.on('GET', '/api/budgets', (_) => okResponse(budgets));
    // The home screen also asks for the number on its notification bell.
    backend.on('GET', '/api/notifications/unread-count', (_) => okResponse(0));
  });

  int budgetRequests() =>
      backend.calls.where((call) => call == _budgetsCall).length;

  Future<void> pump(WidgetTester tester, Widget app) async {
    // Tall, so that every list item is built without scrolling, and wide, because the test font (Ahem) is much wider
    // than a real one and the rows of the home screen would overflow at phone width.
    tester.view.physicalSize = const Size(600, 3000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(app);
    await tester.pumpAndSettle();
  }

  Future<void> dismissDialog(WidgetTester tester) async {
    expect(find.byType(Dialog), findsOneWidget);
    await tester.tap(find.text('Đã hiểu'));
    await tester.pumpAndSettle();
  }

  Future<void> reloadAll(WidgetTester tester, _Tabs tabs) async {
    tabs.dashboard.currentState!.reload();
    tabs.ledger.currentState!.reload();
    await tester.pumpAndSettle();
  }

  group('Dashboard', () {
    late GlobalKey<DashboardPageState> dashboard;
    late Widget app;

    setUp(() {
      dashboard = GlobalKey<DashboardPageState>();
      app = MaterialApp(
        // The bell of the home screen reads its tooltip from the app's localizations.
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: DashboardPage(key: dashboard)),
      );
    });

    testWidgets(
      'takes the budgets and the transactions from the server and counts only the matching expenses',
      (tester) async {
        budgets = [_budget()];
        transactions = [
          _tx(400000),
          _tx(90000, id: 2, category: 'Di chuyển'),
          _tx(5000000, id: 3, isExpense: false),
        ];

        await pump(tester, app);

        expect(
          backend.calls,
          containsAll([_budgetsCall, 'GET /api/transactions/mine']),
        );
        expect(find.byType(BudgetProgressCard), findsOneWidget);
        expect(
          find.text('${_money.format(400000)} / ${_money.format(1000000)}'),
          findsOneWidget,
        );
        expect(find.text('40%'), findsOneWidget);
        expect(find.byType(Dialog), findsNothing);
      },
    );

    testWidgets('lists the most used budgets first and at most three of them', (
      tester,
    ) async {
      budgets = [
        _budget(id: 1, category: 'Ăn uống'),
        _budget(id: 2, category: 'Di chuyển'),
        _budget(id: 3, category: 'Giải trí'),
        _budget(id: 4, category: 'Mua sắm'),
      ];
      transactions = [
        _tx(100000, id: 1, category: 'Ăn uống'), // 10%
        _tx(700000, id: 2, category: 'Di chuyển'), // 70%
        _tx(400000, id: 3, category: 'Giải trí'), // 40%
        _tx(200000, id: 4, category: 'Mua sắm'), // 20%
      ];

      await pump(tester, app);

      expect(find.byType(BudgetProgressCard), findsNWidgets(3));
      expect(find.text('Ăn uống'), findsNothing);
      expect(find.text('+1 ngân sách khác'), findsOneWidget);
      double top(String name) => tester.getTopLeft(find.text(name)).dy;
      expect(top('Di chuyển'), lessThan(top('Giải trí')));
      expect(top('Giải trí'), lessThan(top('Mua sắm')));
    });

    testWidgets('a budget without a category counts every expense', (
      tester,
    ) async {
      budgets = [_budget(category: null)];
      transactions = [_tx(400000), _tx(90000, id: 2, category: 'Di chuyển')];

      await pump(tester, app);

      expect(find.text('Tất cả danh mục'), findsOneWidget);
      expect(find.text('49%'), findsOneWidget);
    });

    testWidgets('a weekly budget is measured over its own week only', (
      tester,
    ) async {
      final start = _weekStart();
      final end = DateTime(start.year, start.month, start.day + 6);
      budgets = [
        _budget(category: null, periodType: 'WEEK', start: start, end: end),
      ];
      transactions = [
        _tx(100000),
        _tx(
          900000,
          id: 2,
          date: DateTime(start.year, start.month, start.day - 3, 12),
        ),
      ];

      await pump(tester, app);

      expect(
        find.text('Tuần · ${_day.format(start)} – ${_day.format(end)}'),
        findsOneWidget,
      );
      expect(find.text('10%'), findsOneWidget);
    });

    testWidgets(
      '80% opens one warning, and reloading again and again never repeats it',
      (tester) async {
        budgets = [_budget()];
        transactions = [_tx(850000)];

        await pump(tester, app);
        expect(find.text(_warningTitle), findsOneWidget);
        await dismissDialog(tester);

        dashboard.currentState!.reload();
        await tester.pumpAndSettle();
        await tester.fling(
          find.byType(CustomScrollView),
          const Offset(0, 300),
          1000,
        ); // pull to refresh
        await tester.pumpAndSettle();
        dashboard.currentState!.reload();
        await tester.pumpAndSettle();

        expect(find.byType(Dialog), findsNothing);
        expect(
          budgetRequests(),
          greaterThanOrEqualTo(3),
          reason: 'every reload did ask the server again',
        );
      },
    );

    testWidgets(
      'passing 100% later opens one error, and the warning is not repeated with it',
      (tester) async {
        budgets = [_budget()];
        transactions = [_tx(850000)];
        await pump(tester, app);
        await dismissDialog(tester);

        transactions = [_tx(1100000)];
        dashboard.currentState!.reload();
        await tester.pumpAndSettle();

        expect(find.byType(Dialog), findsOneWidget);
        expect(find.text(_exceededTitle), findsOneWidget);
        expect(find.text('110%'), findsWidgets);
        await dismissDialog(tester);

        dashboard.currentState!.reload();
        await tester.pumpAndSettle();
        expect(find.byType(Dialog), findsNothing);
      },
    );

    testWidgets(
      'a budget that could not be loaded leaves the rest of the screen working, says so, and never alerts',
      (tester) async {
        backend.on('GET', '/api/budgets', (_) => jsonResponse(500, {}));
        transactions = [_tx(850000)];

        await withCapturedLogs((logs) async {
          await pump(tester, app);

          expect(
            logs.where((log) => log.startsWith('Lỗi tải ngân sách')),
            hasLength(1),
          );
        });
        expect(find.text(_loadFailedNote), findsOneWidget);
        expect(find.text('Giao dịch gần đây'), findsOneWidget);
        expect(find.byType(BudgetProgressCard), findsNothing);
        expect(find.byType(Dialog), findsNothing);

        backend.on('GET', '/api/budgets', (_) => okResponse([_budget()]));
        dashboard.currentState!.reload();
        await tester.pumpAndSettle();

        expect(find.text(_loadFailedNote), findsNothing);
        expect(find.byType(BudgetProgressCard), findsOneWidget);
        expect(find.text(_warningTitle), findsOneWidget);
      },
    );

    testWidgets(
      'leaving the screen while it is still loading does not touch the disposed screen and shows no alert',
      (tester) async {
        final pending = Completer<ResponseBody>();
        backend.on('GET', '/api/budgets', (_) => pending.future);
        transactions = [_tx(850000)];

        await withCapturedLogs((logs) async {
          await tester.pumpWidget(app);
          await tester.pump();
          await tester.pumpWidget(const MaterialApp(home: SizedBox()));
          pending.complete(okResponse([_budget()]));
          await tester.pumpAndSettle();

          expect(
            logs,
            isEmpty,
            reason: 'nothing may fail, not even quietly inside a catch',
          );
        });
        expect(tester.takeException(), isNull);
        expect(find.byType(Dialog), findsNothing);
      },
    );

    testWidgets(
      'when the home data itself cannot be loaded the loading state ends and the failure is logged',
      (tester) async {
        backend.on(
          'GET',
          '/api/transactions/mine',
          (_) => jsonResponse(500, {}),
        );
        budgets = [_budget()];

        await withCapturedLogs((logs) async {
          await pump(tester, app);

          expect(
            logs.where((log) => log.startsWith('Lỗi tải trang chủ')),
            hasLength(1),
          );
        });
        expect(find.text('Xin chào,'), findsOneWidget);
        expect(find.byType(Dialog), findsNothing);
      },
    );

    testWidgets(
      'asks for the budgets at the same time as the transactions, not after them',
      (tester) async {
        final pending = Completer<ResponseBody>();
        backend.on('GET', '/api/transactions/mine', (_) => pending.future);

        await tester.pumpWidget(app);
        await tester.pump(
          const Duration(milliseconds: 100),
        ); // long enough for Dio to send both requests

        expect(backend.calls, contains('GET /api/transactions/mine'));
        expect(
          backend.calls,
          contains(_budgetsCall),
          reason: 'the transactions have not answered yet',
        );
        pending.complete(okResponse([]));
        await tester.pumpAndSettle();
      },
    );

    testWidgets(
      'without any active budget there is no budget block and no alert',
      (tester) async {
        transactions = [_tx(850000)];

        await pump(tester, app);

        expect(find.text('Ngân sách'), findsNothing);
        expect(find.byType(BudgetProgressCard), findsNothing);
        expect(find.byType(Dialog), findsNothing);
      },
    );

    testWidgets('a budget of another period is not shown on the home screen', (
      tester,
    ) async {
      final lastMonthEnd = DateTime(_today().year, _today().month, 0);
      budgets = [
        _budget(
          start: DateTime(lastMonthEnd.year, lastMonthEnd.month, 1),
          end: lastMonthEnd,
        ),
      ];
      transactions = [_tx(850000)];

      await pump(tester, app);

      expect(find.byType(BudgetProgressCard), findsNothing);
      expect(find.byType(Dialog), findsNothing);
    });

    testWidgets(
      '"Quản lý" opens the budget screen and the home screen reloads when the user comes back',
      (tester) async {
        budgets = [_budget()];
        transactions = [_tx(400000)];
        await pump(tester, app);
        expect(budgetRequests(), 1);

        await tester.tap(find.text('Quản lý'));
        await tester.pumpAndSettle();
        expect(find.byType(BudgetScreen), findsOneWidget);
        final whileOpen = budgetRequests();

        await tester.pageBack();
        await tester.pumpAndSettle();

        expect(find.byType(BudgetScreen), findsNothing);
        expect(budgetRequests(), whileOpen + 1);
      },
    );
  });

  group('Ledger', () {
    late GlobalKey<LedgerPageState> ledger;
    late Widget app;

    setUp(() {
      ledger = GlobalKey<LedgerPageState>();
      app = MaterialApp(home: LedgerPage(key: ledger));
    });

    testWidgets(
      'shows the budgets that overlap the month on screen, each measured over its own dates',
      (tester) async {
        final start = _weekStart();
        budgets = [
          _budget(),
          _budget(
            id: 2,
            category: null,
            amount: 2000000,
            periodType: 'WEEK',
            start: start,
            end: DateTime(start.year, start.month, start.day + 6),
          ),
        ];
        final nextMonthStart = DateTime(_today().year, _today().month + 1, 1);
        final lastMonthEnd = DateTime(_today().year, _today().month, 0);
        budgets.addAll([
          // Starts on the first day after the month, and ends on the last day before it: neither is in the month.
          _budget(
            id: 3,
            periodType: 'WEEK',
            start: nextMonthStart,
            end: DateTime(nextMonthStart.year, nextMonthStart.month, 7),
          ),
          _budget(
            id: 4,
            category: null,
            periodType: 'WEEK',
            start: lastMonthEnd,
            end: lastMonthEnd,
          ),
        ]);
        transactions = [_tx(400000), _tx(100000, id: 2, category: 'Di chuyển')];

        await pump(tester, app);

        expect(backend.calls, contains(_budgetsCall));
        expect(find.byType(BudgetProgressCard), findsNWidgets(2));
        expect(find.text('40%'), findsOneWidget);
        expect(find.text('25%'), findsOneWidget);
      },
    );

    testWidgets(
      'moving to another month filters the budgets in memory, without asking the server again',
      (tester) async {
        budgets = [_budget()];
        transactions = [_tx(400000)];
        await pump(tester, app);
        expect(find.byType(BudgetProgressCard), findsOneWidget);
        final requests = budgetRequests();

        await tester.tap(find.byIcon(Icons.chevron_right_rounded));
        await tester.pumpAndSettle();
        expect(find.byType(BudgetProgressCard), findsNothing);
        expect(find.text(_emptyPeriodNote), findsOneWidget);

        await tester.tap(find.byIcon(Icons.chevron_left_rounded));
        await tester.pumpAndSettle();
        expect(find.byType(BudgetProgressCard), findsOneWidget);
        expect(budgetRequests(), requests);
      },
    );

    testWidgets(
      'the week view keeps the budgets that touch the week and drops the others',
      (tester) async {
        final thisWeek = _weekStart();
        final lastWeekStart = DateTime(
          thisWeek.year,
          thisWeek.month,
          thisWeek.day - 7,
        );
        final nextWeekStart = DateTime(
          thisWeek.year,
          thisWeek.month,
          thisWeek.day + 7,
        );
        budgets = [
          _budget(),
          _budget(
            id: 2,
            periodType: 'WEEK',
            start: lastWeekStart,
            end: DateTime(
              lastWeekStart.year,
              lastWeekStart.month,
              lastWeekStart.day + 6,
            ),
          ),
          _budget(
            id: 3,
            periodType: 'WEEK',
            start: nextWeekStart,
            end: DateTime(
              nextWeekStart.year,
              nextWeekStart.month,
              nextWeekStart.day + 6,
            ),
          ),
        ];
        transactions = [_tx(400000)];
        await pump(tester, app);

        await tester.tap(find.text('Tháng'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Tuần').last);
        await tester.pumpAndSettle();

        expect(find.byType(BudgetProgressCard), findsOneWidget);
        expect(find.textContaining('Tháng · '), findsOneWidget);
      },
    );

    testWidgets(
      'picking a day in another week of the month measures the budgets again for the week on screen',
      (tester) async {
        final start = _weekStart();
        budgets = [
          _budget(),
          _budget(
            id: 2,
            periodType: 'WEEK',
            start: start,
            end: DateTime(start.year, start.month, start.day + 6),
          ),
        ];
        transactions = [_tx(400000)];
        await pump(tester, app);
        await tester.tap(find.text('Tháng'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Tuần').last);
        await tester.pumpAndSettle();
        expect(find.byType(BudgetProgressCard), findsNWidgets(2));
        final requests = budgetRequests();

        final farDay = _today().day <= 15
            ? 28
            : 1; // at least a week from today, so in another week of this month
        await tester.tap(find.text('Lịch'));
        await tester.pumpAndSettle();
        await tester.tap(
          find.descendant(
            of: find.byType(CalendarDatePicker),
            matching: find.text('$farDay'),
          ),
        );
        await tester.tap(
          find.descendant(
            of: find.byType(DatePickerDialog),
            matching: find.text('OK'),
          ),
        );
        await tester.pumpAndSettle();

        expect(
          find.byType(BudgetProgressCard),
          findsOneWidget,
          reason: 'only the monthly budget touches that week',
        );
        expect(find.textContaining('Tháng · '), findsOneWidget);
        expect(
          budgetRequests(),
          requests,
          reason: 'the month did not change, so nothing is fetched again',
        );
      },
    );

    testWidgets(
      'leaving the screen while it is still loading does not touch the disposed screen and shows no alert',
      (tester) async {
        final pending = Completer<ResponseBody>();
        backend.on('GET', '/api/budgets', (_) => pending.future);
        transactions = [_tx(850000)];

        await tester.pumpWidget(app);
        await tester.pump();
        await tester.pumpWidget(const MaterialApp(home: SizedBox()));
        pending.complete(okResponse([_budget()]));
        await tester.pumpAndSettle();

        expect(tester.takeException(), isNull);
        expect(find.byType(Dialog), findsNothing);
      },
    );

    testWidgets(
      'asks for the budgets at the same time as the transactions, not after them',
      (tester) async {
        final pending = Completer<ResponseBody>();
        backend.on('GET', '/api/transactions/mine', (_) => pending.future);

        await tester.pumpWidget(app);
        await tester.pump(
          const Duration(milliseconds: 100),
        ); // long enough for Dio to send both requests

        expect(backend.calls, contains('GET /api/transactions/mine'));
        expect(
          backend.calls,
          contains(_budgetsCall),
          reason: 'the transactions have not answered yet',
        );
        pending.complete(okResponse([]));
        await tester.pumpAndSettle();
      },
    );

    testWidgets('80% opens one warning, and a reload does not repeat it', (
      tester,
    ) async {
      budgets = [_budget()];
      transactions = [_tx(850000)];

      await pump(tester, app);
      expect(find.text(_warningTitle), findsOneWidget);
      expect(
        find.textContaining('Sắp chạm hạn mức · Còn lại: '),
        findsOneWidget,
      );
      await dismissDialog(tester);

      ledger.currentState!.reload();
      await tester.pumpAndSettle();

      expect(find.byType(Dialog), findsNothing);
    });

    testWidgets(
      'a budget that could not be loaded is reported inline and never alerts',
      (tester) async {
        backend.on('GET', '/api/budgets', (_) => jsonResponse(500, {}));
        transactions = [_tx(850000)];

        await withCapturedLogs((logs) async {
          await pump(tester, app);

          expect(
            logs.where((log) => log.startsWith('Lỗi tải ngân sách')),
            hasLength(1),
          );
        });
        expect(find.text(_loadFailedNote), findsOneWidget);
        expect(find.byType(Dialog), findsNothing);
        expect(find.text('Tổng chi'), findsOneWidget);
      },
    );
  });

  group('Home screen and ledger together', () {
    testWidgets(
      'both load the same budgets at start-up, and only one dialog opens',
      (tester) async {
        budgets = [_budget()];
        transactions = [_tx(850000)];
        final tabs = _Tabs();

        await pump(tester, tabs.app);

        expect(
          budgetRequests(),
          2,
          reason: 'each page asked for the budgets, so each could have alerted',
        );
        expect(find.byType(Dialog), findsOneWidget);
        expect(find.text(_warningTitle), findsOneWidget);
        await dismissDialog(tester);
        expect(
          find.byType(Dialog),
          findsNothing,
          reason: 'the second page had nothing new to say',
        );
      },
    );

    testWidgets('changing tabs and reloading both pages stays quiet', (
      tester,
    ) async {
      budgets = [_budget()];
      transactions = [_tx(850000)];
      final tabs = _Tabs();
      await pump(tester, tabs.app);
      await dismissDialog(tester);

      tabs.index.value = 1;
      await tester.pumpAndSettle();
      expect(
        find.byType(BudgetProgressCard),
        findsOneWidget,
        reason: 'the ledger is on screen now',
      );
      tabs.index.value = 0;
      await tester.pumpAndSettle();
      await reloadAll(tester, tabs);
      await reloadAll(tester, tabs);

      expect(find.byType(Dialog), findsNothing);
      expect(budgetRequests(), 6);
    });

    testWidgets(
      'crossing 100% is announced once, however many pages notice it',
      (tester) async {
        budgets = [_budget()];
        transactions = [_tx(850000)];
        final tabs = _Tabs();
        await pump(tester, tabs.app);
        await dismissDialog(tester);

        transactions = [_tx(1100000)];
        await reloadAll(tester, tabs);
        expect(find.byType(Dialog), findsOneWidget);
        expect(find.text(_exceededTitle), findsOneWidget);
        await dismissDialog(tester);

        await reloadAll(tester, tabs);
        expect(find.byType(Dialog), findsNothing);
      },
    );

    testWidgets(
      'after the app is closed and opened again the alerts already seen stay quiet',
      (tester) async {
        budgets = [_budget()];
        transactions = [_tx(850000)];
        await pump(tester, _Tabs().app);
        await dismissDialog(tester);

        await tester.pumpWidget(const SizedBox());
        BudgetAlertTracker.instance
            .resetMemory(); // what the process forgets; SharedPreferences stays
        BudgetAlertPresenter.resetQueue();
        await pump(tester, _Tabs().app);

        expect(
          find.byType(BudgetProgressCard),
          findsOneWidget,
          reason: 'the home screen shows its card again, silently',
        );
        expect(find.byType(Dialog), findsNothing);
      },
    );
  });
}
