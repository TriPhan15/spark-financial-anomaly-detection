import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/constants/transaction_categories.dart';
import 'package:finance_ai_app/l10n/app_localizations.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/dashboard/dashboard_page.dart';
import 'package:finance_ai_app/screens/ledger/ledger_page.dart';
import 'package:finance_ai_app/services/budget_alert_tracker.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/widgets/budget/budget_alert_presenter.dart';
import 'package:finance_ai_app/widgets/receipt/receipt_status_badge.dart';

import '../../helpers/fake_backend.dart';
import '../../helpers/fake_local_store.dart';

const String _processingLabel = 'Đang xử lý';
const String _successLabel = 'Hoàn tất';
const String _failedLabel = 'Lỗi · cần xác nhận lại';
const String _editLabel = 'Sửa giao dịch';
const String _deleteLabel = 'Xóa giao dịch này';

final NumberFormat _money = NumberFormat.currency(
  locale: 'vi_VN',
  symbol: 'đ',
  decimalDigits: 0,
);

final List<Map<String, Object?>> _serverCategories = [
  {'id': 10, 'name': 'Ăn uống', 'type': 'EXPENSE'},
  {'id': 11, 'name': 'Di chuyển', 'type': 'EXPENSE'},
  {'id': 6, 'name': 'Thu nhập', 'type': 'INCOME'},
];

/// A transaction the way core-backend sends it; [receiptStatus] is left out for a transaction without a receipt.
Map<String, Object?> _tx(int id, {num amount = 45000, String? receiptStatus}) =>
    {
      'id': id,
      'title': 'Giao dịch $id',
      'amount': amount,
      'date': DateTime.now().toIso8601String(),
      'category': 'Ăn uống',
      'isExpense': true,
      'receiptStatus': ?receiptStatus,
    };

void main() {
  final backend = FakeBackend();
  var transactions = <Map<String, Object?>>[];

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
    // The lists also read the transactions waiting to be sent; the fake keeps the test away from a real database.
    AppRepository.instance.localStore = FakeLocalStore();
  });

  setUp(() async {
    // SharedPreferences shares one Future that must be created inside the test body, which runs in fake async, so the
    // store is reset after the session is written and the body starts from scratch (see budget_display_test.dart).
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    SharedPreferences.setMockInitialValues({});
    BudgetAlertTracker.instance.resetMemory();
    BudgetAlertPresenter.resetQueue();

    // The category lists are static, so a test that loads them would otherwise feed the next one.
    TransactionCategories.expenseSelectable.clear();
    TransactionCategories.incomeSelectable.clear();

    backend.reset();
    transactions = [];
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
    backend.on('GET', '/api/budgets', (_) => okResponse([]));
    backend.on('GET', '/api/categories', (_) => okResponse(_serverCategories));
    // The home screen also asks for the number on its notification bell.
    backend.on('GET', '/api/notifications/unread-count', (_) => okResponse(0));
  });

  Future<void> pump(WidgetTester tester, Widget app) async {
    // Tall, so that every row is built without scrolling, and wide, because the test font is much wider than a real one.
    tester.view.physicalSize = const Size(600, 3000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(app);
    await tester.pumpAndSettle();
  }

  Future<void> tapText(WidgetTester tester, String text) async {
    await tester.tap(find.text(text));
    await tester.pumpAndSettle();
  }

  group('Ledger', () {
    const app = MaterialApp(home: LedgerPage());

    Finder rowOf(int id) => find.ancestor(
      of: find.text('Giao dịch $id'),
      matching: find.byType(InkWell),
    );

    Finder inRow(int id, String label) =>
        find.descendant(of: rowOf(id), matching: find.text(label));

    testWidgets(
      'each row shows the state of its receipt, and rows without one show none',
      (tester) async {
        transactions = [
          _tx(1, receiptStatus: 'PENDING'),
          _tx(2, receiptStatus: 'success'),
          _tx(3, receiptStatus: 'FAILED'),
          _tx(4),
          _tx(5, receiptStatus: 'CANCELED'),
        ];

        await pump(tester, app);

        expect(find.byType(ReceiptStatusBadge), findsNWidgets(3));
        expect(inRow(1, _processingLabel), findsOneWidget);
        expect(inRow(2, _successLabel), findsOneWidget);
        expect(inRow(3, _failedLabel), findsOneWidget);
        expect(
          find.descendant(
            of: rowOf(4),
            matching: find.byType(ReceiptStatusBadge),
          ),
          findsNothing,
        );
        expect(
          find.descendant(
            of: rowOf(5),
            matching: find.byType(ReceiptStatusBadge),
          ),
          findsNothing,
        );
      },
    );

    testWidgets(
      'a status the server adds later is left out instead of breaking the list',
      (tester) async {
        transactions = [
          _tx(1, receiptStatus: 'SOMETHING_NEW'),
          _tx(2, receiptStatus: 'SUCCESS'),
        ];

        await pump(tester, app);

        expect(find.text('Giao dịch 1'), findsOneWidget);
        expect(find.byType(ReceiptStatusBadge), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );

    testWidgets(
      'the details show the state of the receipt and offer to edit and to delete the transaction',
      (tester) async {
        transactions = [_tx(1, receiptStatus: 'FAILED')];
        await pump(tester, app);

        await tapText(tester, 'Giao dịch 1');

        expect(find.text('Trạng thái hóa đơn:'), findsOneWidget);
        expect(
          find.text(_failedLabel),
          findsNWidgets(2),
          reason: 'once in the list and once in the details',
        );
        expect(find.text(_editLabel), findsOneWidget);
        expect(find.text(_deleteLabel), findsOneWidget);
      },
    );

    testWidgets(
      'the details of a transaction without a receipt have no status row',
      (tester) async {
        transactions = [_tx(1)];
        await pump(tester, app);

        await tapText(tester, 'Giao dịch 1');

        expect(find.text('Trạng thái hóa đơn:'), findsNothing);
        expect(find.byType(ReceiptStatusBadge), findsNothing);
        expect(find.text(_editLabel), findsOneWidget);
      },
    );

    testWidgets(
      'offline there is no edit button, and deleting is still offered as before',
      (tester) async {
        transactions = [_tx(1)];
        await pump(tester, app);
        // The user is in local mode by the time the details open; the data is already on screen.
        addTearDown(() => AppConfig.mode = AppMode.full);
        AppConfig.mode = AppMode.demo;

        await tapText(tester, 'Giao dịch 1');

        expect(find.text(_editLabel), findsNothing);
        expect(find.text(_deleteLabel), findsOneWidget);
      },
    );

    testWidgets(
      'editing from the details PUTs the change, then the ledger shows the new amount',
      (tester) async {
        transactions = [_tx(1, amount: 45000, receiptStatus: 'FAILED')];
        backend.on('PUT', '/api/transactions/1', (_) {
          transactions = [_tx(1, amount: 50000, receiptStatus: 'FAILED')];
          return okResponse(transactions.single);
        });
        await pump(tester, app);
        expect(find.text('- ${_money.format(45000)}'), findsOneWidget);

        await tapText(tester, 'Giao dịch 1');
        await tapText(tester, _editLabel);

        expect(
          find.text('Lưu thay đổi'),
          findsOneWidget,
          reason: 'the edit form is open',
        );
        await tester.enterText(find.byType(TextField).first, '50000');
        await tapText(tester, 'Lưu thay đổi');
        await tapText(tester, 'OK');

        final puts = backend.requests.where((r) => r.method == 'PUT').toList();
        expect(puts, hasLength(1));
        expect(puts.single.path, '/api/transactions/1');
        expect(puts.single.data, {'amount': 50000.0});
        expect(
          find.text('Lưu thay đổi'),
          findsNothing,
          reason: 'back on the ledger',
        );
        expect(find.text('- ${_money.format(50000)}'), findsOneWidget);
        expect(find.text('- ${_money.format(45000)}'), findsNothing);
      },
    );

    testWidgets('closing the edit form without saving changes nothing', (
      tester,
    ) async {
      transactions = [_tx(1)];
      await pump(tester, app);
      final requestsBefore = backend.requests.length;

      await tapText(tester, 'Giao dịch 1');
      await tapText(tester, _editLabel);
      await tester.tap(find.byIcon(Icons.close_rounded));
      await tester.pumpAndSettle();

      expect(find.text('Lưu thay đổi'), findsNothing);
      expect(find.text('- ${_money.format(45000)}'), findsOneWidget);
      expect(backend.requests.where((r) => r.method == 'PUT'), isEmpty);
      expect(
        backend.requests
            .skip(requestsBefore)
            .map((r) => '${r.method} ${r.path}'),
        ['GET /api/categories'],
        reason:
            'only the category list is fetched: no reload after a form that saved nothing',
      );
    });
  });

  group('Dashboard', () {
    testWidgets('the recent transactions carry the badge of their receipt', (
      tester,
    ) async {
      transactions = [_tx(1, receiptStatus: 'SUCCESS'), _tx(2)];

      await pump(
        tester,
        // The bell of the home screen reads its tooltip from the app's localizations.
        const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(body: DashboardPage()),
        ),
      );

      expect(find.text('Giao dịch 1'), findsOneWidget);
      expect(find.text('Giao dịch 2'), findsOneWidget);
      expect(find.byType(ReceiptStatusBadge), findsOneWidget);
      expect(find.text(_successLabel), findsOneWidget);
    });
  });
}
