// Level 4: what the user sees and what gets saved on the three entry paths, when the model flags the expense. The Big Data
// answer is a stand-in here (the real model is judged in level 3), so a dialog can be provoked on demand.
// Needs levels 1 to 3 green. Worst first: a flagged expense must not be saved behind the user's back (Hủy), and the
// "Vẫn lưu" choice must still save exactly what the user declared.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/constants/transaction_categories.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/ledger/add_transaction_page.dart';
import 'package:finance_ai_app/screens/ledger/edit_transaction_page.dart';
import 'package:finance_ai_app/screens/receipt_scanner/review_form_page.dart';
import 'package:finance_ai_app/services/anomaly_check_service.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../helpers/captured_logs.dart';
import '../helpers/fake_backend.dart';
import '../helpers/fake_local_store.dart';
import 'support.dart';

const String _checkout = '/api/transactions/checkout';
const String _predict = '$stubFraudApi/v2/predict-anomaly';
const String _warning = 'Chi tiêu bất thường so với thói quen';
const String _photo = 'assets/images/logo.png';

final NumberFormat _money = NumberFormat.currency(locale: 'vi_VN', symbol: 'đ', decimalDigits: 0);

void main() {
  final core = FakeBackend();
  late FakeLocalStore store;

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: core);
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    SharedPreferences.setMockInitialValues({});
    TransactionCategories.expenseSelectable.clear();
    TransactionCategories.incomeSelectable.clear();
    store = FakeLocalStore();
    AppRepository.instance.localStore = store;

    core.reset();
    core.on('GET', '/api/categories', (_) => okResponse([
      {'id': 10, 'name': 'Ăn uống', 'type': 'EXPENSE'},
      {'id': 11, 'name': 'Di chuyển', 'type': 'EXPENSE'},
      {'id': 6, 'name': 'Thu nhập', 'type': 'INCOME'},
    ]));
    core.on('GET', '/api/transactions/mine', (_) => okResponse(habitHistory()));
    core.on('POST', _checkout, (_) => okResponse({'transactionId': 5}));
    core.on('PUT', '/api/transactions/5', (_) => okResponse({'id': 5}));
    core.on('POST', '/donut/scan', (_) => jsonResponse(200, {
      'seller': {'seller_name': 'Highlands', 'timestamp': '20/09/2026 08:30', 'total_cost_number': 45000},
    }));
    core.on('POST', '/api/upload', (_) => okResponse({'url': 'https://img.test/r.png'}));
    useFraud(anomaly: true);
  });

  tearDown(() {
    Intl.defaultLocale = null;
    AppConfig.mode = AppMode.full;
  });

  Future<void> bigScreen(WidgetTester tester) async {
    tester.view.physicalSize = const Size(600, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
  }

  Future<void> tap(WidgetTester tester, Finder finder) async {
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  // ---------------------------------------------------------------- add (manual entry)
  Future<void> openAdd(WidgetTester tester) async {
    await bigScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: AddTransactionPage()));
    await tester.pumpAndSettle();
  }

  Future<void> submitAdd(WidgetTester tester, String amount) async {
    await tester.enterText(find.byType(TextField).at(0), amount);
    await tap(tester, find.text('Lưu giao dịch'));
  }

  int fraudCalls() => _currentFraud!.requests.where((r) => r.path == _predict).length;
  int checkouts() => core.requests.where((r) => r.method == 'POST' && r.path == _checkout).length;
  int puts() => core.requests.where((r) => r.method == 'PUT').length;

  group('L4-A manual entry (Thêm giao dịch)', () {
    testWidgets('L4-01 flagged expense: the warning appears and Hủy saves NOTHING', (tester) async {
      await openAdd(tester);
      await submitAdd(tester, '9000000');

      expect(find.text(_warning), findsOneWidget);
      await tap(tester, find.text('Hủy'));

      expect(checkouts(), 0, reason: 'nothing is sent to core-backend');
      expect(store.rows, isEmpty, reason: 'and nothing is kept on the device');
      expect(find.text('Thêm giao dịch'), findsOneWidget, reason: 'the form stays open for the user to correct');
    });

    testWidgets('L4-02 flagged expense: Vẫn lưu saves exactly the declared amount, once', (tester) async {
      await openAdd(tester);
      await submitAdd(tester, '9000000');
      await tap(tester, find.text('Vẫn lưu'));

      expect(checkouts(), 1);
      expect(bodyOf(core, 'POST', _checkout)['amount'], 9000000.0);
      expect(find.text('Đã thêm giao dịch mới.'), findsOneWidget);
    });

    testWidgets('L4-03 the model is asked with the typed amount and category, and the history', (tester) async {
      await openAdd(tester);
      await submitAdd(tester, '9000000');

      final body = bodyOf(_currentFraud!, 'POST', _predict);
      expect((body['current'] as Map)['amount'], 9000000.0);
      expect((body['current'] as Map)['category'], isNotEmpty);
      expect((body['history'] as List).length, lessThanOrEqualTo(100));
      await tap(tester, find.text('Hủy'));
    });

    testWidgets('L4-04 Big Data API fails (500): the expense is saved with no warning (fail-open)', (tester) {
      return withCapturedLogs((logs) async {
        useFraud(anomaly: true, status: 500);
        await openAdd(tester);
        await submitAdd(tester, '9000000');

        expect(find.text(_warning), findsNothing);
        expect(checkouts(), 1);
      });
    });

    testWidgets('L4-05 income (Tiền thu) is never sent to the model', (tester) async {
      await openAdd(tester);
      await tap(tester, find.text('Tiền thu'));
      await submitAdd(tester, '9000000');

      expect(fraudCalls(), 0);
      expect(find.text(_warning), findsNothing);
      expect(checkouts(), 1);
    });
  });

  // ---------------------------------------------------------------- edit
  TransactionModel old() => TransactionModel(
    id: '5',
    title: 'Cà phê Highlands',
    amount: 45000,
    date: DateTime(2026, 9, 20, 8, 30),
    category: 'Ăn uống',
    isExpense: true,
  );

  Future<void> openEdit(WidgetTester tester) async {
    await bigScreen(tester);
    await tester.pumpWidget(MaterialApp(home: EditTransactionPage(transaction: old())));
    await tester.pumpAndSettle();
  }

  group('L4-B editing an expense (Sửa giao dịch)', () {
    testWidgets('L4-06 inflating the amount: warning, and Hủy sends NO update', (tester) async {
      await openEdit(tester);
      await tester.enterText(find.byType(TextField).first, '9000000');
      await tap(tester, find.text('Lưu thay đổi'));

      expect(find.text(_warning), findsOneWidget);
      await tap(tester, find.text('Hủy'));
      expect(puts(), 0);
    });

    testWidgets('L4-07 inflating the amount: Vẫn lưu updates the server with the new amount', (tester) async {
      await openEdit(tester);
      await tester.enterText(find.byType(TextField).first, '9000000');
      await tap(tester, find.text('Lưu thay đổi'));
      await tap(tester, find.text('Vẫn lưu'));

      expect(puts(), 1);
      expect(core.requests.firstWhere((r) => r.method == 'PUT').data, {'amount': 9000000.0});
    });

    testWidgets('L4-08 the check uses the date of the edited expense, not today', (tester) async {
      await openEdit(tester);
      await tester.enterText(find.byType(TextField).first, '9000000');
      await tap(tester, find.text('Lưu thay đổi'));

      final current = bodyOf(_currentFraud!, 'POST', _predict)['current'] as Map;
      expect(current['occurredAt'], '2026-09-20T08:30:00');
      await tap(tester, find.text('Hủy'));
    });

    testWidgets('L4-09 changing only the note is not an amount change: no model call', (tester) async {
      await openEdit(tester);
      await tester.enterText(find.byType(TextField).at(1), 'Cà phê sáng');
      await tap(tester, find.text('Lưu thay đổi'));

      expect(fraudCalls(), 0);
      expect(find.text(_warning), findsNothing);
      expect(puts(), 1);
    });
  });

  // ---------------------------------------------------------------- receipt scan + pencil
  /// The review form once the scan has answered. Reading the photo is real file I/O, which the fake clock of the test does
  /// not run: it gets real time first, then the pumps run the request and the rebuild.
  Future<void> openReview(WidgetTester tester) async {
    await bigScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: ReviewFormPage(imagePath: _photo)));
    for (var i = 0; i < 80 && find.text('Highlands').evaluate().isEmpty; i++) {
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
      await tester.pump(const Duration(milliseconds: 100));
    }
    await tester.pumpAndSettle();
    expect(find.text('Highlands'), findsOneWidget);
  }

  Future<void> useFraudAmountPencil(WidgetTester tester, String amount) async {
    await tap(tester, find.byIcon(Icons.edit_rounded));
    expect(find.text('Sửa tổng tiền'), findsOneWidget);
    await tester.enterText(find.descendant(of: find.byType(AlertDialog), matching: find.byType(TextField)), amount);
    await tap(tester, find.text('Áp dụng'));
  }

  group('L4-C receipt scan with the pencil (Quét hóa đơn)', () {
    testWidgets('L4-10 pencil: the declared total replaces the OCR total and is marked "(đã sửa)"', (tester) async {
      await openReview(tester);
      expect(find.text(_money.format(45000)), findsNWidgets(2));
      expect(find.text('(đã sửa)'), findsNothing);

      await useFraudAmountPencil(tester, '9999000');

      expect(find.text('(đã sửa)'), findsOneWidget);
      expect(find.text(_money.format(9999000)), findsNWidgets(2));
      expect(find.text(_money.format(45000)), findsNothing);
    });

    testWidgets('L4-11 pencil, then Lưu: the model is asked about the DECLARED amount, and Hủy saves nothing', (tester) async {
      await openReview(tester);
      await useFraudAmountPencil(tester, '9999000');
      await tap(tester, find.text('Xác nhận & Lưu CSDL'));

      expect((bodyOf(_currentFraud!, 'POST', _predict)['current'] as Map)['amount'], 9999000.0);
      expect(find.text(_warning), findsOneWidget);
      await tap(tester, find.text('Hủy'));
      expect(checkouts(), 0);
    });

    testWidgets('L4-12 pencil, flagged, Vẫn lưu: the declared amount is what core-backend receives (scan data kept)', (tester) async {
      await openReview(tester);
      await useFraudAmountPencil(tester, '9999000');
      await tap(tester, find.text('Xác nhận & Lưu CSDL'));
      await tap(tester, find.text('Vẫn lưu'));

      final body = bodyOf(core, 'POST', _checkout);
      expect(body['amount'], 9999000.0);
      expect(body['isExpense'], true);
      expect(body['rawReceiptData'], contains('Highlands'), reason: 'the original OCR output is still stored for audit');
    });

    testWidgets('L4-13 OCR could not read the total (0): the pencil lets the user type it and it is saved', (tester) async {
      core.on('POST', '/donut/scan', (_) => jsonResponse(200, {
        'seller': {'seller_name': 'Highlands', 'timestamp': '20/09/2026 08:30', 'total_cost_number': 0},
      }));
      useFraud(anomaly: false);
      await openReview(tester);
      await useFraudAmountPencil(tester, '65000');
      await tap(tester, find.text('Xác nhận & Lưu CSDL'));

      expect(bodyOf(core, 'POST', _checkout)['amount'], 65000.0);
    });

    testWidgets('L4-14 the pencil dialog refuses 0 and text: the total does not change', (tester) async {
      await openReview(tester);
      await tap(tester, find.byIcon(Icons.edit_rounded));
      await tester.enterText(find.descendant(of: find.byType(AlertDialog), matching: find.byType(TextField)), 'abc');
      await tap(tester, find.text('Áp dụng'));

      expect(find.text('Sửa tổng tiền'), findsOneWidget, reason: 'the dialog stays open');
      await tap(tester, find.text('Hủy'));
      expect(find.text('(đã sửa)'), findsNothing);
      expect(find.text(_money.format(45000)), findsNWidgets(2));
    });
  });

  // ---------------------------------------------------------------- best cases
  group('L5 the normal path (nothing unusual)', () {
    testWidgets('L5-01 a normal scanned receipt (not touched) is saved with no warning', (tester) async {
      useFraud(anomaly: false);
      await openReview(tester);
      await tap(tester, find.text('Xác nhận & Lưu CSDL'));

      expect(find.text(_warning), findsNothing);
      expect(bodyOf(core, 'POST', _checkout)['amount'], 45000.0);
    });

    testWidgets('L5-02 a normal manual expense is saved with no warning', (tester) async {
      useFraud(anomaly: false);
      await openAdd(tester);
      await submitAdd(tester, '52000');

      expect(find.text(_warning), findsNothing);
      expect(checkouts(), 1);
      expect(find.text('Đã thêm giao dịch mới.'), findsOneWidget);
    });

    testWidgets('L5-03 a normal edit is saved with no warning', (tester) async {
      useFraud(anomaly: false);
      await openEdit(tester);
      await tester.enterText(find.byType(TextField).first, '50000');
      await tap(tester, find.text('Lưu thay đổi'));

      expect(find.text(_warning), findsNothing);
      expect(puts(), 1);
    });

    testWidgets('L5-04 a rule flag shows its own wording, not a misleading 2% risk score', (tester) async {
      final ruleStub = FakeBackend()
        ..on('POST', _predict, (_) => jsonResponse(200, {
          'scored': true,
          'isAnomaly': true,
          'riskScore': 0.019,
          'threshold': 0.961,
          'explanation': 'Số tiền gấp 98,0 lần mức chi trung bình của bạn cho danh mục này.',
          'modelVersion': 'behavior-gbt-2026-10-07',
          'flaggedBy': 'rule',
        }));
      AnomalyCheckService.configureForTest(baseUrl: stubFraudApi, adapter: ruleStub);
      _currentFraud = ruleStub;
      await openAdd(tester);
      await submitAdd(tester, '5000000');

      expect(find.text(_warning), findsOneWidget);
      expect(find.textContaining('Số tiền gấp 98,0 lần'), findsOneWidget);
      expect(find.textContaining('Điểm rủi ro'), findsNothing);
      expect(find.textContaining('vượt xa mức bạn thường chi'), findsOneWidget);
      await tap(tester, find.text('Hủy'));
    });
  });
}

/// Replaces the stand-in Big Data API with one that answers [anomaly] (or a [status] error).
void useFraud({required bool anomaly, int status = 200}) {
  final stub = stubFraudBackend(anomaly: anomaly, status: status);
  AnomalyCheckService.configureForTest(baseUrl: stubFraudApi, adapter: stub);
  _currentFraud = stub;
}

FakeBackend? _currentFraud;
