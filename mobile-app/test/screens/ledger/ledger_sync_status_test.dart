import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/constants/transaction_categories.dart';
import 'package:finance_ai_app/l10n/app_localizations.dart';
import 'package:finance_ai_app/models/sync_status.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/dashboard/dashboard_page.dart';
import 'package:finance_ai_app/screens/ledger/ledger_page.dart';
import 'package:finance_ai_app/services/budget_alert_tracker.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/widgets/budget/budget_alert_presenter.dart';
import 'package:finance_ai_app/widgets/sync/sync_status_badge.dart';

import '../../helpers/captured_logs.dart';
import '../../helpers/fake_backend.dart';
import '../../helpers/fake_local_store.dart';

const String _checkout = '/api/transactions/checkout';
const String _mine = '/api/transactions/mine';
const String _errorLabel = 'Gửi lỗi';
const String _pendingLabel = 'Chờ gửi';
const String _resendLabel = 'Gửi lại';
const String _editLabel = 'Sửa giao dịch';
const String _deleteLabel = 'Xóa giao dịch này';
const String _loadFailedNotice =
    'Không tải được danh sách từ máy chủ. Chỉ hiển thị các giao dịch chưa gửi trên thiết bị. '
    'Kéo xuống để thử lại.';

final List<Map<String, Object?>> _serverCategories = [
  {'id': 10, 'name': 'Ăn uống', 'type': 'EXPENSE'},
  {'id': 6, 'name': 'Thu nhập', 'type': 'INCOME'},
];

/// A transaction the way core-backend sends it.
Map<String, Object?> _serverRow(int id, {String? title}) => {
  'id': id,
  'title': title ?? 'Giao dịch $id',
  'amount': 45000,
  'date': DateTime.now().toIso8601String(),
  'category': 'Ăn uống',
  'isExpense': true,
};

/// A transaction created on this device that the server has not confirmed.
TransactionModel _unsent(String title, SyncStatus status) =>
    TransactionModel.draft(
      title: title,
      category: 'Ăn uống',
      amount: 30000,
      date: DateTime.now(),
      isExpense: true,
    ).withSyncStatus(status);

/// The network is gone: the request never reaches the server.
Never _noNetwork(RequestOptions options) => throw DioException(
  requestOptions: options,
  type: DioExceptionType.connectionError,
  error: 'no network',
);

void main() {
  final backend = FakeBackend();
  late FakeLocalStore store;
  var onServer = <Map<String, Object?>>[];

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
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

    store = FakeLocalStore();
    AppRepository.instance.localStore = store;
    backend.reset();
    onServer = [];
    backend.on(
      'GET',
      '/api/users/me',
      (_) => okResponse({'id': 1, 'email': 'an@example.com'}),
    );
    backend.on('GET', _mine, (_) => okResponse(onServer));
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

  List<RequestOptions> checkoutPosts() => backend.requests
      .where((r) => r.method == 'POST' && r.path == _checkout)
      .toList();

  group('Ledger', () {
    const app = MaterialApp(home: LedgerPage());

    Finder rowOf(String title) =>
        find.ancestor(of: find.text(title), matching: find.byType(InkWell));

    Finder inRow(String title, String label) =>
        find.descendant(of: rowOf(title), matching: find.text(label));

    testWidgets(
      'a transaction waiting to be sent is listed with its state, next to the ones of the server',
      (tester) async {
        onServer = [_serverRow(1)];
        await store.insertTransaction(_unsent('Chưa gửi', SyncStatus.error), 1);
        await store.insertTransaction(
          _unsent('Đang chờ', SyncStatus.pending),
          1,
        );

        await pump(tester, app);

        expect(find.text('Giao dịch 1'), findsOneWidget);
        expect(find.byType(SyncStatusBadge), findsNWidgets(2));
        expect(inRow('Chưa gửi', _errorLabel), findsOneWidget);
        expect(inRow('Đang chờ', _pendingLabel), findsOneWidget);
        expect(
          find.descendant(
            of: rowOf('Giao dịch 1'),
            matching: find.byType(SyncStatusBadge),
          ),
          findsNothing,
        );
        expect(find.text(_loadFailedNotice), findsNothing);
      },
    );

    testWidgets('a transaction the server has confirmed shows no sync state', (
      tester,
    ) async {
      onServer = [_serverRow(1)];
      await store.insertTransaction(_unsent('Đã gửi', SyncStatus.synced), 1);

      await pump(tester, app);

      expect(
        find.text('Đã gửi'),
        findsNothing,
        reason: 'the server list is the one that shows it',
      );
      expect(find.byType(SyncStatusBadge), findsNothing);
    });

    testWidgets(
      'when the server cannot be reached the unsent transactions are still listed, with a notice',
      (tester) {
        return withCapturedLogs((logs) async {
          backend.on('GET', _mine, _noNetwork);
          await store.insertTransaction(
            _unsent('Chưa gửi', SyncStatus.error),
            1,
          );

          await pump(tester, app);

          expect(find.text('Chưa gửi'), findsOneWidget);
          expect(inRow('Chưa gửi', _errorLabel), findsOneWidget);
          expect(find.text(_loadFailedNotice), findsOneWidget);
          expect(
            logs.where((line) => line.contains('Lỗi tải giao dịch')),
            isNotEmpty,
          );
          expect(tester.takeException(), isNull);
        });
      },
    );

    testWidgets(
      'when the server cannot be reached and nothing is waiting, the ledger is empty with the notice',
      (tester) {
        return withCapturedLogs((logs) async {
          backend.on('GET', _mine, _noNetwork);

          await pump(tester, app);

          expect(
            find.byType(CircularProgressIndicator),
            findsNothing,
            reason: 'the spinner does not turn for ever',
          );
          expect(find.text(_loadFailedNotice), findsOneWidget);
          expect(find.text('Không có giao dịch trong kỳ này.'), findsOneWidget);
        });
      },
    );

    testWidgets(
      'the details of an unsent transaction offer to resend and to delete it, but not to edit it',
      (tester) async {
        await store.insertTransaction(_unsent('Chưa gửi', SyncStatus.error), 1);
        await pump(tester, app);

        await tapText(tester, 'Chưa gửi');

        expect(find.text('Đồng bộ:'), findsOneWidget);
        expect(find.text(_resendLabel), findsOneWidget);
        expect(
          find.text(_editLabel),
          findsNothing,
          reason: 'the server has no copy to edit',
        );
        expect(find.text(_deleteLabel), findsOneWidget);
      },
    );

    testWidgets(
      'the details of a transaction of the server have no sync row and no resend button',
      (tester) async {
        onServer = [_serverRow(1)];
        await pump(tester, app);

        await tapText(tester, 'Giao dịch 1');

        expect(find.text('Đồng bộ:'), findsNothing);
        expect(find.text(_resendLabel), findsNothing);
        expect(find.text(_editLabel), findsOneWidget);
      },
    );

    testWidgets(
      'resending posts the same clientId, flags the transaction synced and refreshes the list',
      (tester) async {
        final unsent = _unsent('Chưa gửi', SyncStatus.error);
        await store.insertTransaction(unsent, 1);
        backend.on('POST', _checkout, (_) {
          onServer = [
            _serverRow(7, title: 'Chưa gửi'),
          ]; // the server has it now
          return okResponse({'transactionId': 7});
        });
        await pump(tester, app);
        expect(inRow('Chưa gửi', _errorLabel), findsOneWidget);

        await tapText(tester, 'Chưa gửi');
        await tapText(tester, _resendLabel);

        expect(checkoutPosts(), hasLength(1));
        expect(
          (checkoutPosts().single.data as Map)['clientId'],
          unsent.clientId,
        );
        expect(
          store.byClientId(unsent.clientId!)!.syncStatus,
          SyncStatus.synced,
        );
        expect(find.text('Đã gửi giao dịch lên máy chủ'), findsOneWidget);
        expect(find.byType(SyncStatusBadge), findsNothing);
        expect(
          find.text('Chưa gửi'),
          findsOneWidget,
          reason: 'listed once, from the server, and no longer twice',
        );
      },
    );

    testWidgets(
      'when the resend fails again the user is told, and the transaction stays flagged',
      (tester) {
        return withCapturedLogs((logs) async {
          final unsent = _unsent('Chưa gửi', SyncStatus.error);
          await store.insertTransaction(unsent, 1);
          backend.on('POST', _checkout, _noNetwork);
          await pump(tester, app);

          await tapText(tester, 'Chưa gửi');
          await tapText(tester, _resendLabel);

          expect(find.text('Vẫn chưa gửi được'), findsOneWidget);
          await tapText(tester, 'Đã hiểu');

          expect(
            store.byClientId(unsent.clientId!)!.syncStatus,
            SyncStatus.error,
          );
          expect(inRow('Chưa gửi', _errorLabel), findsOneWidget);
          expect(checkoutPosts(), hasLength(1));
        });
      },
    );

    testWidgets(
      'a refusal of the server on a resend shows its reason and keeps the transaction',
      (tester) {
        return withCapturedLogs((logs) async {
          final unsent = _unsent('Chưa gửi', SyncStatus.error);
          await store.insertTransaction(unsent, 1);
          backend.on(
            'POST',
            _checkout,
            (_) => jsonResponse(400, {
              'code': 400,
              'message': 'Danh mục không tồn tại',
            }),
          );
          await pump(tester, app);

          await tapText(tester, 'Chưa gửi');
          await tapText(tester, _resendLabel);

          expect(find.text('Gửi lại thất bại'), findsOneWidget);
          expect(find.text('Danh mục không tồn tại'), findsOneWidget);
          await tapText(tester, 'Đã hiểu');

          expect(
            store.byClientId(unsent.clientId!)!.syncStatus,
            SyncStatus.error,
          );
          expect(
            find.text('Chưa gửi'),
            findsOneWidget,
            reason: 'still listed, so the user can delete it',
          );
        });
      },
    );

    testWidgets(
      'deleting an unsent transaction removes it from the device only, never on the server',
      (tester) async {
        onServer = [_serverRow(1)];
        await store.insertTransaction(_unsent('Chưa gửi', SyncStatus.error), 1);
        await pump(tester, app);

        await tapText(tester, 'Chưa gửi');
        await tapText(tester, _deleteLabel);
        expect(find.text('Xóa giao dịch chưa gửi?'), findsOneWidget);
        await tapText(tester, 'Xóa ngay');

        expect(store.rows, isEmpty);
        expect(
          backend.requests.where((r) => r.method == 'DELETE'),
          isEmpty,
          reason: 'a local row number is not a server id',
        );
        expect(find.text('Chưa gửi'), findsNothing);
        expect(find.text('Giao dịch 1'), findsOneWidget);
        expect(find.text('Đã xóa giao dịch chưa gửi'), findsOneWidget);
      },
    );

    testWidgets('cancelling the delete keeps the transaction', (tester) async {
      await store.insertTransaction(_unsent('Chưa gửi', SyncStatus.error), 1);
      await pump(tester, app);

      await tapText(tester, 'Chưa gửi');
      await tapText(tester, _deleteLabel);
      await tapText(tester, 'Hủy');

      expect(store.rows, hasLength(1));
      expect(find.text('Chưa gửi'), findsOneWidget);
    });
  });

  group('Dashboard', () {
    testWidgets(
      'the recent transactions carry the sync state of the ones waiting to be sent',
      (tester) async {
        onServer = [_serverRow(1)];
        await store.insertTransaction(_unsent('Chưa gửi', SyncStatus.error), 1);

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
        expect(find.text('Chưa gửi'), findsOneWidget);
        expect(find.byType(SyncStatusBadge), findsOneWidget);
        expect(find.text(_errorLabel), findsOneWidget);
      },
    );
  });
}
