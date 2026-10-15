import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/models/budget_model.dart';
import 'package:finance_ai_app/screens/budget/budget_screen.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../../helpers/fake_backend.dart';

Map<String, Object?> _budget({
  int id = 7,
  int? categoryId = 10,
  String? categoryName = 'Ăn uống',
  num amount = 2000000,
  String periodType = 'MONTH',
  String startDate = '2026-09-01',
  String endDate = '2026-09-30',
}) => {
  'id': id,
  'categoryId': categoryId,
  'categoryName': categoryName,
  'amount': amount,
  'periodType': periodType,
  'startDate': startDate,
  'endDate': endDate,
};

final List<Map<String, Object?>> _serverCategories = [
  {'id': 10, 'name': 'Ăn uống', 'type': 'EXPENSE'},
  {'id': 11, 'name': 'Di chuyển', 'type': 'EXPENSE'},
  {'id': 6, 'name': 'Thu nhập', 'type': 'INCOME'},
];

void main() {
  final backend = FakeBackend();

  setUpAll(() {
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    backend.reset();
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
  });

  tearDown(() async {
    await SessionService.clearSession();
  });

  Future<void> pumpScreen(WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: BudgetScreen()));
    await tester.pumpAndSettle();
  }

  Future<void> tapText(WidgetTester tester, String text) async {
    await tester.tap(find.text(text));
    await tester.pumpAndSettle();
  }

  group('listing', () {
    testWidgets(
      'shows the budgets of the server with category, period, dates and amount, asking without any user id',
      (tester) async {
        backend.on(
          'GET',
          '/api/budgets',
          (_) => okResponse([
            _budget(),
            _budget(
              id: 8,
              categoryId: null,
              categoryName: null,
              amount: 500000,
              periodType: 'WEEK',
              startDate: '2026-09-14',
              endDate: '2026-09-20',
            ),
          ]),
        );

        await pumpScreen(tester);

        expect(find.text('Ăn uống'), findsOneWidget);
        expect(find.text('Tất cả danh mục'), findsOneWidget);
        expect(find.text('Tháng · 01/09/2026 – 30/09/2026'), findsOneWidget);
        expect(find.text('Tuần · 14/09/2026 – 20/09/2026'), findsOneWidget);
        expect(find.text('2,000,000 đ'), findsOneWidget);
        expect(find.text('500,000 đ'), findsOneWidget);

        expect(backend.calls, ['GET /api/budgets']);
        expect(backend.requests.single.queryParameters, isEmpty);
        expect(backend.requests.single.data, isNull);
      },
    );

    testWidgets('shows the empty state with a way to create the first budget', (
      tester,
    ) async {
      backend.on('GET', '/api/budgets', (_) => okResponse([]));

      await pumpScreen(tester);

      expect(find.text('Chưa có ngân sách nào.'), findsOneWidget);
      expect(find.text('Thiết lập ngân sách'), findsOneWidget);
    });

    testWidgets(
      'without a login session it asks the user to log in again and calls nothing',
      (tester) async {
        await SessionService.clearSession();

        await pumpScreen(tester);

        expect(find.text('Phiên đăng nhập hết hạn'), findsOneWidget);
        expect(backend.calls, isEmpty);
        await tapText(tester, 'Đã hiểu');
        expect(tester.takeException(), isNull);
      },
    );

    testWidgets('an expired login (403) is reported as such', (tester) async {
      backend.on('GET', '/api/budgets', (_) => jsonResponse(403, {}));

      await pumpScreen(tester);

      expect(find.text('Không tải được ngân sách'), findsOneWidget);
      expect(
        find.text('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.'),
        findsOneWidget,
      );
    });

    testWidgets(
      'a server error without a message gets a generic one, and the empty state follows',
      (tester) async {
        backend.on('GET', '/api/budgets', (_) => jsonResponse(500, {}));

        await pumpScreen(tester);

        expect(
          find.text('Không thể kết nối máy chủ. Vui lòng thử lại.'),
          findsOneWidget,
        );
        await tapText(tester, 'Đã hiểu');
        expect(find.text('Chưa có ngân sách nào.'), findsOneWidget);
      },
    );

    testWidgets(
      'leaving the screen while the budgets are loading does not touch the disposed screen',
      (tester) async {
        final pending = Completer<ResponseBody>();
        backend.on('GET', '/api/budgets', (_) => pending.future);

        await tester.pumpWidget(const MaterialApp(home: BudgetScreen()));
        await tester.pump();
        await tester.pumpWidget(const MaterialApp(home: SizedBox()));
        pending.complete(okResponse([_budget()]));
        await tester.pumpAndSettle();

        expect(tester.takeException(), isNull);
      },
    );
  });

  group('creating', () {
    testWidgets(
      'POSTs the contract JSON, without a user, and shows the reloaded list',
      (tester) async {
        var listCalls = 0;
        backend.on(
          'GET',
          '/api/budgets',
          (_) => okResponse(
            listCalls++ == 0 ? [] : [_budget(id: 9, amount: 3000000)],
          ),
        );
        backend.on(
          'GET',
          '/api/categories',
          (_) => okResponse(_serverCategories),
        );
        backend.on(
          'POST',
          '/api/budgets',
          (_) => okResponse(_budget(id: 9, amount: 3000000), status: 201),
        );

        await pumpScreen(tester);
        await tester.tap(find.byIcon(Icons.add_rounded));
        await tester.pumpAndSettle();

        // Only expense categories are offered.
        expect(find.widgetWithText(ChoiceChip, 'Ăn uống'), findsOneWidget);
        expect(find.widgetWithText(ChoiceChip, 'Di chuyển'), findsOneWidget);
        expect(find.widgetWithText(ChoiceChip, 'Thu nhập'), findsNothing);
        expect(
          tester
              .widget<ChoiceChip>(find.widgetWithText(ChoiceChip, 'Tuần'))
              .onSelected,
          isNotNull,
        );

        await tester.enterText(find.byType(TextField), '3000000');
        await tester.tap(find.widgetWithText(ChoiceChip, 'Ăn uống'));
        await tester.pumpAndSettle();
        await tapText(tester, 'Lưu ngân sách');

        expect(find.text('Đã lưu ngân sách'), findsOneWidget);
        await tapText(tester, 'OK');

        final month = BudgetPeriodType.rangeContaining(
          BudgetPeriodType.month,
          DateTime.now(),
        );
        final post = backend.requests.singleWhere((r) => r.method == 'POST');
        expect(post.path, '/api/budgets');
        expect(post.queryParameters, isEmpty);
        expect(post.data, {
          'categoryId': 10,
          'amount': 3000000.0,
          'periodType': 'MONTH',
          'startDate': BudgetModel.formatApiDate(month.start),
          'endDate': BudgetModel.formatApiDate(month.end),
        });
        expect(
          backend.calls.last,
          'GET /api/budgets',
          reason: 'the list is reloaded after saving',
        );
        expect(find.text('3,000,000 đ'), findsOneWidget);
        expect(
          find.text('Lưu ngân sách'),
          findsNothing,
          reason: 'the form is closed',
        );
      },
    );

    testWidgets('a budget for every category is sent with a null category', (
      tester,
    ) async {
      backend.on('GET', '/api/budgets', (_) => okResponse([]));
      backend.on(
        'GET',
        '/api/categories',
        (_) => okResponse(_serverCategories),
      );
      backend.on(
        'POST',
        '/api/budgets',
        (_) => okResponse(
          _budget(categoryId: null, categoryName: null),
          status: 201,
        ),
      );

      await pumpScreen(tester);
      await tester.tap(find.text('Thiết lập ngân sách'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '1000000');
      await tapText(tester, 'Lưu ngân sách');

      final post = backend.requests.singleWhere((r) => r.method == 'POST');
      expect((post.data as Map)['categoryId'], isNull);
      expect((post.data as Map).containsKey('categoryId'), isTrue);
    });

    testWidgets(
      'a duplicate (409) is shown with the message of the server and the form stays open',
      (tester) async {
        backend.on('GET', '/api/budgets', (_) => okResponse([]));
        backend.on(
          'GET',
          '/api/categories',
          (_) => okResponse(_serverCategories),
        );
        backend.on(
          'POST',
          '/api/budgets',
          (_) => jsonResponse(409, {
            'code': 409,
            'message': 'Ngân sách cho danh mục và kỳ hạn này đã tồn tại',
          }),
        );

        await pumpScreen(tester);
        await tester.tap(find.byIcon(Icons.add_rounded));
        await tester.pumpAndSettle();
        await tester.enterText(find.byType(TextField), '1000000');
        await tapText(tester, 'Lưu ngân sách');

        expect(find.text('Không lưu được ngân sách'), findsOneWidget);
        expect(
          find.text('Ngân sách cho danh mục và kỳ hạn này đã tồn tại'),
          findsOneWidget,
        );
        await tapText(tester, 'Đã hiểu');
        expect(find.text('Lưu ngân sách'), findsOneWidget);
      },
    );
  });

  group('editing', () {
    testWidgets(
      'PUTs to /api/budgets/{id} with the changed amount and the unchanged dates',
      (tester) async {
        var listCalls = 0;
        backend.on(
          'GET',
          '/api/budgets',
          (_) => okResponse([
            _budget(amount: listCalls++ == 0 ? 2000000 : 2500000),
          ]),
        );
        backend.on(
          'GET',
          '/api/categories',
          (_) => okResponse(_serverCategories),
        );
        backend.on(
          'PUT',
          '/api/budgets/7',
          (_) => okResponse(_budget(amount: 2500000)),
        );

        await pumpScreen(tester);
        await tapText(tester, 'Ăn uống');

        expect(find.text('Sửa ngân sách'), findsOneWidget);
        expect(
          tester.widget<TextField>(find.byType(TextField)).controller!.text,
          '2000000',
        );

        await tester.enterText(find.byType(TextField), '2500000');
        await tapText(tester, 'Lưu ngân sách');
        await tapText(tester, 'OK');

        final put = backend.requests.singleWhere((r) => r.method == 'PUT');
        expect(put.path, '/api/budgets/7');
        expect(put.queryParameters, isEmpty);
        expect(put.data, {
          'categoryId': 10,
          'amount': 2500000.0,
          'periodType': 'MONTH',
          'startDate': '2026-09-01',
          'endDate': '2026-09-30',
        });
        expect(backend.requests.where((r) => r.method == 'POST'), isEmpty);
        expect(find.text('2,500,000 đ'), findsOneWidget);
      },
    );
  });

  group('deleting', () {
    testWidgets(
      'asks first, then DELETEs /api/budgets/{id} and drops the card',
      (tester) async {
        backend.on('GET', '/api/budgets', (_) => okResponse([_budget()]));
        backend.on(
          'DELETE',
          '/api/budgets/7',
          (_) => okResponse(null, message: 'Xóa ngân sách thành công'),
        );

        await pumpScreen(tester);
        await tester.tap(find.byIcon(Icons.delete_outline_rounded));
        await tester.pumpAndSettle();

        expect(find.text('Xóa ngân sách?'), findsOneWidget);
        expect(backend.calls, [
          'GET /api/budgets',
        ], reason: 'nothing is deleted before the user confirms');

        await tapText(tester, 'Xóa');
        expect(find.text('Đã xóa ngân sách'), findsOneWidget);
        await tapText(tester, 'OK');

        expect(backend.calls, ['GET /api/budgets', 'DELETE /api/budgets/7']);
        expect(find.text('Ăn uống'), findsNothing);
        expect(find.text('Chưa có ngân sách nào.'), findsOneWidget);
      },
    );

    testWidgets('cancelling the question deletes nothing', (tester) async {
      backend.on('GET', '/api/budgets', (_) => okResponse([_budget()]));

      await pumpScreen(tester);
      await tester.tap(find.byIcon(Icons.delete_outline_rounded));
      await tester.pumpAndSettle();
      await tapText(tester, 'Hủy');

      expect(backend.calls, ['GET /api/budgets']);
      expect(find.text('Ăn uống'), findsOneWidget);
    });

    testWidgets(
      'a refused delete shows the message of the server and keeps the card',
      (tester) async {
        backend.on('GET', '/api/budgets', (_) => okResponse([_budget()]));
        backend.on(
          'DELETE',
          '/api/budgets/7',
          (_) => jsonResponse(404, {
            'code': 404,
            'message': 'Không tìm thấy ngân sách',
          }),
        );

        await pumpScreen(tester);
        await tester.tap(find.byIcon(Icons.delete_outline_rounded));
        await tester.pumpAndSettle();
        await tapText(tester, 'Xóa');

        expect(find.text('Không xóa được ngân sách'), findsOneWidget);
        expect(find.text('Không tìm thấy ngân sách'), findsOneWidget);
        await tapText(tester, 'Đã hiểu');
        expect(find.text('Ăn uống'), findsOneWidget);
      },
    );

    testWidgets(
      'leaving the screen while the delete is running does not touch the disposed screen',
      (tester) async {
        final pending = Completer<ResponseBody>();
        backend.on('GET', '/api/budgets', (_) => okResponse([_budget()]));
        backend.on('DELETE', '/api/budgets/7', (_) => pending.future);

        await pumpScreen(tester);
        await tester.tap(find.byIcon(Icons.delete_outline_rounded));
        await tester.pumpAndSettle();
        await tapText(tester, 'Xóa');

        await tester.pumpWidget(const MaterialApp(home: SizedBox()));
        pending.complete(okResponse(null));
        await tester.pumpAndSettle();

        expect(tester.takeException(), isNull);
      },
    );
  });
}
