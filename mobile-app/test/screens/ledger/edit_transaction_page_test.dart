import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/core/constants/transaction_categories.dart';
import 'package:finance_ai_app/models/receipt_status.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/screens/ledger/edit_transaction_page.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/widgets/receipt/receipt_status_badge.dart';

import '../../helpers/captured_logs.dart';
import '../../helpers/fake_backend.dart';

const String _putPath = '/api/transactions/5';
const String _saveLabel = 'Lưu thay đổi';
const String _savingLabel = 'Đang lưu...';
const String _failedTitle = 'Không lưu được giao dịch';

final List<Map<String, Object?>> _serverCategories = [
  {'id': 10, 'name': 'Ăn uống', 'type': 'EXPENSE'},
  {'id': 11, 'name': 'Di chuyển', 'type': 'EXPENSE'},
  {'id': 6, 'name': 'Thu nhập', 'type': 'INCOME'},
];

TransactionModel _tx({
  String? id = '5',
  String title = 'Cà phê Highlands',
  double amount = 45000,
  String category = 'Ăn uống',
  bool isExpense = true,
  ReceiptStatus? receiptStatus,
}) {
  return TransactionModel(
    id: id,
    title: title,
    amount: amount,
    date: DateTime(2026, 9, 20, 8, 30),
    category: category,
    isExpense: isExpense,
    receiptStatus: receiptStatus,
  );
}

/// Stands for the Ledger: a button that opens the form and reports what the form popped with.
class _Launcher extends StatelessWidget {
  final TransactionModel transaction;
  final void Function(bool? saved) onClosed;

  const _Launcher({required this.transaction, required this.onClosed});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: ElevatedButton(
          onPressed: () async {
            final saved = await Navigator.push<bool>(
              context,
              MaterialPageRoute(
                builder: (_) => EditTransactionPage(transaction: transaction),
              ),
            );
            onClosed(saved);
          },
          child: const Text('open'),
        ),
      ),
    );
  }
}

void main() {
  final backend = FakeBackend();
  var closed = false;
  bool? result;

  setUpAll(() {
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
  });

  setUp(() async {
    // The session is written here, outside the fake async of the test body, and the store is reset again afterwards.
    SharedPreferences.setMockInitialValues({});
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    SharedPreferences.setMockInitialValues({});

    // The category lists are static, so a test that loads them would otherwise feed the next one.
    TransactionCategories.expenseSelectable.clear();
    TransactionCategories.incomeSelectable.clear();

    closed = false;
    result = null;
    backend.reset();
    backend.on('GET', '/api/categories', (_) => okResponse(_serverCategories));
  });

  Future<void> openForm(
    WidgetTester tester,
    TransactionModel transaction,
  ) async {
    // Tall, so that the whole form is on screen, and wide, because the test font is much wider than a real one.
    tester.view.physicalSize = const Size(600, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      MaterialApp(
        home: _Launcher(
          transaction: transaction,
          onClosed: (saved) {
            closed = true;
            result = saved;
          },
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  Future<void> tapText(WidgetTester tester, String text) async {
    await tester.tap(find.text(text));
    await tester.pumpAndSettle();
  }

  TextField field(WidgetTester tester, int index) =>
      tester.widget<TextField>(find.byType(TextField).at(index));

  ChoiceChip chip(WidgetTester tester, String label) =>
      tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, label));

  Future<void> typeAmount(WidgetTester tester, String text) =>
      tester.enterText(find.byType(TextField).at(0), text);

  Future<void> typeNote(WidgetTester tester, String text) =>
      tester.enterText(find.byType(TextField).at(1), text);

  List<RequestOptions> puts() =>
      backend.requests.where((r) => r.method == 'PUT').toList();

  group('opening', () {
    testWidgets(
      'shows the amount, note and category of the transaction, and what the form does not change',
      (tester) async {
        await openForm(tester, _tx());

        expect(find.text('Sửa giao dịch'), findsOneWidget);
        expect(field(tester, 0).controller!.text, '45000');
        expect(field(tester, 1).controller!.text, 'Cà phê Highlands');
        expect(chip(tester, 'Ăn uống').selected, isTrue);
        expect(chip(tester, 'Di chuyển').selected, isFalse);
        expect(
          find.text('Thu nhập'),
          findsNothing,
          reason: 'an expense is only offered the expense categories',
        );
        expect(find.text('Khoản chi'), findsOneWidget);
        expect(find.text('20/09/2026 08:30'), findsOneWidget);
      },
    );

    testWidgets('an income is offered the income categories', (tester) async {
      await openForm(
        tester,
        _tx(isExpense: false, category: 'Thu nhập', title: 'Lương tháng 9'),
      );

      expect(find.text('Khoản thu'), findsOneWidget);
      expect(chip(tester, 'Thu nhập').selected, isTrue);
      expect(find.text('Ăn uống'), findsNothing);
    });

    testWidgets(
      'a title equal to the category means the transaction has no note',
      (tester) async {
        await openForm(tester, _tx(title: 'Ăn uống'));

        expect(field(tester, 1).controller!.text, isEmpty);
      },
    );

    testWidgets(
      'the category of the transaction stays selectable even when the server list lacks it',
      (tester) async {
        await openForm(tester, _tx(category: 'Khác', title: 'Khác'));

        expect(chip(tester, 'Khác').selected, isTrue);
        expect(find.byType(ChoiceChip), findsNWidgets(3));
      },
    );

    testWidgets(
      'when the categories cannot be loaded the current one stays and a hint says so',
      (tester) {
        return withCapturedLogs((logs) async {
          backend.on('GET', '/api/categories', (_) => jsonResponse(500, {}));

          await openForm(tester, _tx());

          expect(find.byType(ChoiceChip), findsOneWidget);
          expect(chip(tester, 'Ăn uống').selected, isTrue);
          expect(
            find.text(
              'Không tải được danh sách danh mục. Bạn vẫn có thể sửa số tiền và ghi chú.',
            ),
            findsOneWidget,
          );
          expect(
            logs.where((line) => line.contains('Lỗi tải danh mục')),
            isNotEmpty,
          );
        });
      },
    );

    testWidgets('shows the state of the receipt when the transaction has one', (
      tester,
    ) async {
      await openForm(tester, _tx(receiptStatus: ReceiptStatus.failed));

      expect(find.byType(ReceiptStatusBadge), findsOneWidget);
      expect(find.text('Lỗi · cần xác nhận lại'), findsOneWidget);
    });

    testWidgets('shows no badge for a transaction without a receipt', (
      tester,
    ) async {
      await openForm(tester, _tx());

      expect(find.byType(ReceiptStatusBadge), findsNothing);
    });
  });

  group('saving', () {
    testWidgets(
      'a new amount is PUT alone, confirmed, and the form closes with true',
      (tester) async {
        backend.on(
          'PUT',
          _putPath,
          (_) => okResponse({
            'id': 5,
            'amount': 50000,
          }, message: 'Cập nhật giao dịch thành công'),
        );
        await openForm(tester, _tx());

        await typeAmount(tester, '50000');
        await tapText(tester, _saveLabel);

        expect(find.text('Đã lưu thay đổi'), findsOneWidget);
        expect(
          closed,
          isFalse,
          reason: 'the form stays until the user has read the confirmation',
        );
        await tapText(tester, 'OK');

        expect(puts(), hasLength(1));
        expect(puts().single.path, _putPath);
        expect(puts().single.queryParameters, isEmpty);
        expect(puts().single.data, {'amount': 50000.0});
        expect(closed, isTrue);
        expect(result, isTrue);
        expect(find.text('Sửa giao dịch'), findsNothing);
      },
    );

    testWidgets('a new category and note are PUT without the amount', (
      tester,
    ) async {
      backend.on('PUT', _putPath, (_) => okResponse({'id': 5}));
      await openForm(tester, _tx());

      await tapText(tester, 'Di chuyển');
      expect(chip(tester, 'Di chuyển').selected, isTrue);
      expect(chip(tester, 'Ăn uống').selected, isFalse);
      await typeNote(tester, '  Grab đi làm  ');
      await tapText(tester, _saveLabel);
      await tapText(tester, 'OK');

      expect(puts().single.data, {
        'categoryName': 'Di chuyển',
        'note': 'Grab đi làm',
      });
      expect(result, isTrue);
    });

    testWidgets(
      'clearing the note sends an empty note, which clears it on the server',
      (tester) async {
        backend.on('PUT', _putPath, (_) => okResponse({'id': 5}));
        await openForm(tester, _tx());

        await typeNote(tester, '');
        await tapText(tester, _saveLabel);
        await tapText(tester, 'OK');

        expect(puts().single.data, {'note': ''});
      },
    );

    testWidgets('all three fields can change in one request', (tester) async {
      backend.on('PUT', _putPath, (_) => okResponse({'id': 5}));
      await openForm(tester, _tx());

      await typeAmount(tester, '120000');
      await tapText(tester, 'Di chuyển');
      await typeNote(tester, 'Đi sân bay');
      await tapText(tester, _saveLabel);
      await tapText(tester, 'OK');

      expect(puts().single.data, {
        'amount': 120000.0,
        'categoryName': 'Di chuyển',
        'note': 'Đi sân bay',
      });
    });

    testWidgets('saving without a change says so and sends nothing', (
      tester,
    ) async {
      await openForm(tester, _tx());

      await tapText(tester, _saveLabel);

      expect(find.text('Chưa có thay đổi'), findsOneWidget);
      await tapText(tester, 'OK');
      expect(puts(), isEmpty);
      expect(closed, isFalse);
    });

    testWidgets('the same amount typed differently is not a change', (
      tester,
    ) async {
      await openForm(tester, _tx());

      await typeAmount(tester, '45.000 đ');
      await tapText(tester, _saveLabel);

      expect(find.text('Chưa có thay đổi'), findsOneWidget);
      await tapText(tester, 'OK');
      expect(puts(), isEmpty);
    });

    testWidgets(
      'an amount with decimals is shown in whole dong and left alone unless the user changes it',
      (tester) async {
        await openForm(tester, _tx(amount: 45000.5));

        expect(field(tester, 0).controller!.text, '45001');
        await tapText(tester, _saveLabel);

        expect(find.text('Chưa có thay đổi'), findsOneWidget);
        await tapText(tester, 'OK');
        expect(puts(), isEmpty);
      },
    );

    for (final typed in ['', '0', 'abc']) {
      testWidgets('an amount of "$typed" is refused before anything is sent', (
        tester,
      ) async {
        await openForm(tester, _tx());

        await typeAmount(tester, typed);
        await tapText(tester, _saveLabel);

        expect(find.text('Số tiền không hợp lệ'), findsOneWidget);
        await tapText(tester, 'Đã hiểu');
        expect(puts(), isEmpty);
        expect(closed, isFalse);
      });
    }

    testWidgets('a transaction without an id is refused and nothing is sent', (
      tester,
    ) async {
      await openForm(tester, _tx(id: null));

      await typeAmount(tester, '50000');
      await tapText(tester, _saveLabel);

      expect(find.text('Không sửa được giao dịch'), findsOneWidget);
      await tapText(tester, 'Đã hiểu');
      expect(puts(), isEmpty);
    });

    group('without a login session', () {
      setUp(() async {
        await SessionService.clearSession();
        SharedPreferences.setMockInitialValues({});
      });

      testWidgets('asks the user to log in again and sends nothing', (
        tester,
      ) async {
        await openForm(tester, _tx());

        await typeAmount(tester, '50000');
        await tapText(tester, _saveLabel);

        expect(find.text('Phiên đăng nhập hết hạn'), findsOneWidget);
        await tapText(tester, 'Đã hiểu');
        expect(puts(), isEmpty);
        expect(closed, isFalse);
      });
    });
  });

  group('when the server refuses', () {
    /// Saves a new amount against a server that answers with [answer], and checks that the user is told [message],
    /// that the failure is logged once, and that the form stays open and can be saved again.
    Future<void> expectRefusal(
      WidgetTester tester,
      FutureOr<ResponseBody> Function(RequestOptions options) answer,
      String message,
    ) {
      return withCapturedLogs((logs) async {
        backend.on('PUT', _putPath, answer);
        await openForm(tester, _tx());

        await typeAmount(tester, '50000');
        await tapText(tester, _saveLabel);

        expect(find.text(_failedTitle), findsOneWidget);
        expect(find.text(message), findsOneWidget);
        await tapText(tester, 'Đã hiểu');

        expect(closed, isFalse);
        expect(find.text(_saveLabel), findsOneWidget);
        expect(
          tester
              .widget<ElevatedButton>(
                find.widgetWithText(ElevatedButton, _saveLabel),
              )
              .onPressed,
          isNotNull,
        );
        expect(
          logs.where((line) => line.startsWith('Lỗi sửa giao dịch')),
          hasLength(1),
        );
      });
    }

    testWidgets('a rule of the server (400) is shown with its own message', (
      tester,
    ) {
      return expectRefusal(
        tester,
        (_) => jsonResponse(400, {
          'code': 400,
          'message': 'Số dư ví không đủ để thực hiện thay đổi này',
        }),
        'Số dư ví không đủ để thực hiện thay đổi này',
      );
    });

    testWidgets(
      'a transaction of somebody else or a missing one (404) is shown with the message of the server',
      (tester) {
        return expectRefusal(
          tester,
          (_) => jsonResponse(404, {
            'code': 404,
            'message': 'Không tìm thấy giao dịch',
          }),
          'Không tìm thấy giao dịch',
        );
      },
    );

    testWidgets(
      'a refusal without a message (403) tells the user the login has expired',
      (tester) {
        return expectRefusal(
          tester,
          (_) => jsonResponse(403, {}),
          'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
        );
      },
    );

    testWidgets('a server error without a message (500) gets a generic one', (
      tester,
    ) {
      return expectRefusal(
        tester,
        (_) => jsonResponse(500, {}),
        'Máy chủ chưa xử lý được yêu cầu. Vui lòng thử lại sau.',
      );
    });

    testWidgets('no connection tells the user to check the network', (tester) {
      return expectRefusal(
        tester,
        (options) => throw DioException(
          requestOptions: options,
          type: DioExceptionType.connectionError,
        ),
        'Không thể kết nối máy chủ. Vui lòng kiểm tra mạng và thử lại.',
      );
    });
  });

  group('while the request runs', () {
    testWidgets(
      'the button is disabled and says so, so the change cannot be sent twice',
      (tester) async {
        final pending = Completer<ResponseBody>();
        backend.on('PUT', _putPath, (_) => pending.future);
        await openForm(tester, _tx());
        await typeAmount(tester, '50000');

        await tester.tap(find.text(_saveLabel));
        await tester.pump();

        expect(find.text(_savingLabel), findsOneWidget);
        expect(
          tester
              .widget<ElevatedButton>(
                find.widgetWithText(ElevatedButton, _savingLabel),
              )
              .onPressed,
          isNull,
        );

        pending.complete(okResponse({'id': 5}));
        await tester.pumpAndSettle();
        expect(find.text('Đã lưu thay đổi'), findsOneWidget);
        await tapText(tester, 'OK');
        expect(puts(), hasLength(1));
      },
    );

    testWidgets(
      'leaving the form does not touch the disposed form when the answer arrives',
      (tester) async {
        final pending = Completer<ResponseBody>();
        backend.on('PUT', _putPath, (_) => pending.future);
        await openForm(tester, _tx());
        await typeAmount(tester, '50000');
        await tester.tap(find.text(_saveLabel));
        await tester.pump();

        await tester.pumpWidget(const MaterialApp(home: SizedBox()));
        pending.complete(okResponse({'id': 5}));
        await tester.pumpAndSettle();

        expect(tester.takeException(), isNull);
      },
    );
  });
}
