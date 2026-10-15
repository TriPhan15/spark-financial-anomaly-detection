import 'dart:async';
import 'dart:math' as math;

import 'package:dio/dio.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/models/spending_range.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/ai/ai_tab_page.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/widgets/ai/spending_range_filter.dart';

import '../../helpers/captured_logs.dart';
import '../../helpers/fake_backend.dart';
import '../../helpers/fake_local_store.dart';

const String _weekLabel = 'Tuần này';
const String _monthLabel = 'Tháng này';
const String _customLabel = 'Tùy chọn';
const String _refreshLabel = 'Làm mới';
const String _applyLabel = 'Áp dụng';
const String _dismissLabel = 'Đã hiểu';

const String _errorTitle = 'Không phân tích được thói quen chi tiêu';
const String _errorCardTitle = 'Không tải được phân tích';
const String _notEnoughDataTitle = 'Chưa đủ dữ liệu';
const String _networkMessage =
    'Không kết nối được tới máy chủ phân tích. Vui lòng kiểm tra mạng và thử lại.';
const String _serverMessage =
    'Máy chủ phân tích đang gặp sự cố. Vui lòng thử lại sau.';
const String _rejectedMessage =
    'Máy chủ phân tích chưa xử lý được yêu cầu. Vui lòng thử lại sau.';
const String _failureLog = 'Lỗi phân tích thói quen chi tiêu';

/// The patterns the fake AI service answers with, and the texts the page makes of them.
const String _foodThenTransport = 'an uong → di lai';
const String _foodThenTransportShown = 'Ăn uống → Đi lại';
const String _billsThenShopping = 'hoa don → mua sam';
const String _billsThenShoppingShown = 'Hóa đơn → Mua sắm';

final DateTime _now = DateTime.now();

DateTime _at(int year, int month, int day) => DateTime(year, month, day, 12);

/// Today: in this week and in this month.
final DateTime _today = _at(_now.year, _now.month, _now.day);

/// A day of this month outside this Monday-to-Sunday week: the 1st when the week starts after it, else the last day.
final DateTime _monthOnlyDay = () {
  final monday = DateTime(
    _now.year,
    _now.month,
    _now.day - (_now.weekday - DateTime.monday),
  );
  final first = _at(_now.year, _now.month, 1);
  return first.isBefore(monday) ? first : _at(_now.year, _now.month + 1, 0);
}();

/// A day of last month: outside this week and this month.
final DateTime _lastMonthDay = _at(_now.year, _now.month - 1, 15);

/// A transaction the way core-backend sends it.
Map<String, Object?> _tx(
  int id,
  DateTime date, {
  num amount = 1000,
  String category = 'Ăn uống',
  bool isExpense = true,
}) => {
  'id': id,
  'title': 'Giao dịch $id',
  'amount': amount,
  'date': date.toIso8601String(),
  'category': category,
  'isExpense': isExpense,
};

Map<String, Object?> _pattern(String pattern) => {
  'pattern': pattern,
  'count': 4,
  'wsupport': '12.5%',
};

/// What the AI service answers to /spade/analyze.
ResponseBody _patternsResponse(List<Map<String, Object?>> patterns) =>
    jsonResponse(200, {'patterns': patterns});

/// What the month chart must draw for expenses on the given days: days 1-7, 8-14, 15-21 and 22 onwards, worked out
/// here from the days of the month so that the test does not lean on the code it checks.
List<double> _monthBars(Map<DateTime, double> expenseOn) {
  final bars = List.filled(4, 0.0);
  expenseOn.forEach(
    (day, amount) => bars[math.min((day.day - 1) ~/ 7, 3)] += amount,
  );
  return bars;
}

/// What the week chart must draw: one bar per day, Monday first.
List<double> _weekBars(Map<DateTime, double> expenseOn) {
  final bars = List.filled(7, 0.0);
  expenseOn.forEach((day, amount) => bars[day.weekday - 1] += amount);
  return bars;
}

Future<void> _showPage(WidgetTester tester, PickDateRange? pickDateRange) {
  // Tall, so that every card is built without scrolling, and wide, because the test font is much wider than a real one.
  tester.view.physicalSize = const Size(700, 3200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  return tester.pumpWidget(
    MaterialApp(
      home: pickDateRange == null
          ? const AITabPage()
          : AITabPage(pickDateRange: pickDateRange),
    ),
  );
}

/// A few frames without waiting for the animations to stop: a test that keeps an answer of the AI service pending
/// has a spinner that never stops, so pumpAndSettle would time out.
Future<void> _pumpFrames(WidgetTester tester, [int frames = 10]) async {
  for (var i = 0; i < frames; i++) {
    await tester.pump(const Duration(milliseconds: 50));
  }
}

Future<void> _tap(WidgetTester tester, String text) async {
  await tester.tap(find.text(text));
  await tester.pumpAndSettle();
}

String _selectedOption(WidgetTester tester) =>
    [_weekLabel, _monthLabel, _customLabel].singleWhere(
      (label) => tester
          .widget<ChoiceChip>(find.widgetWithText(ChoiceChip, label))
          .selected,
    );

bool _refreshEnabled(WidgetTester tester) =>
    tester
        .widget<TextButton>(find.widgetWithText(TextButton, _refreshLabel))
        .onPressed !=
    null;

List<double> _barHeights(WidgetTester tester) => [
  for (final group
      in tester.widget<BarChart>(find.byType(BarChart)).data.barGroups)
    group.barRods.single.toY,
];

/// The amounts of the transactions a request to the AI service carried, in order.
List<num> _amountsSent(RequestOptions request) {
  final sent = (request.data as Map)['transactions'] as List;
  return [for (final tx in sent) (tx as Map)['amount'] as num]..sort();
}

void main() {
  final backend = FakeBackend();
  var transactions = <Map<String, Object?>>[];
  var patterns = <Map<String, Object?>>[];

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
    // The list of transactions also reads the ones waiting to be sent; the fake keeps the test away from a real database.
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

    backend.reset();
    transactions = [
      _tx(1, _today, amount: 1000),
      _tx(2, _monthOnlyDay, amount: 2000),
      _tx(3, _lastMonthDay, amount: 4000),
      _tx(4, _today, amount: 9000, category: 'Thu nhập', isExpense: false),
    ];
    patterns = [_pattern(_foodThenTransport)];
    backend.on(
      'GET',
      '/api/transactions/mine',
      (_) => okResponse(transactions),
    );
    backend.on('GET', '/api/savings', (_) => okResponse([]));
    backend.on('POST', '/spade/analyze', (_) => _patternsResponse(patterns));
  });

  List<RequestOptions> analyses() =>
      backend.requests.where((r) => r.path == '/spade/analyze').toList();

  Future<void> pumpPage(
    WidgetTester tester, {
    PickDateRange? pickDateRange,
  }) async {
    await _showPage(tester, pickDateRange);
    await tester.pumpAndSettle();
  }

  Future<void> pumpPageWhileLoading(WidgetTester tester) async {
    await _showPage(tester, null);
    await _pumpFrames(tester);
  }

  group('opening the tab', () {
    testWidgets(
      'opens on this month: the transactions of this month are analysed and the patterns are listed',
      (tester) async {
        await pumpPage(tester);

        expect(_selectedOption(tester), _monthLabel);
        expect(analyses(), hasLength(1));
        expect(_amountsSent(analyses().single), [1000, 2000, 9000]);
        expect(find.text(_foodThenTransportShown), findsOneWidget);
        expect(find.text(SpendingRange.month(_now).label), findsOneWidget);
      },
    );

    testWidgets(
      'the request keeps the shape the AI service already knows: only the transactions, each with four fields',
      (tester) async {
        await pumpPage(tester);

        final body = analyses().single.data as Map;
        expect(body.keys, ['transactions']);
        for (final tx in body['transactions'] as List) {
          expect(
            (tx as Map).keys,
            unorderedEquals(['category', 'amount', 'date', 'isExpense']),
          );
        }
      },
    );

    testWidgets(
      'the chart keeps its four weekly bars for the month, with the expenses of each and no income',
      (tester) async {
        await pumpPage(tester);

        expect(
          _barHeights(tester),
          _monthBars({_today: 1000, _monthOnlyDay: 2000}),
        );
        for (final label in ['W1', 'W2', 'W3', 'W4']) {
          expect(find.text(label), findsOneWidget, reason: label);
        }
      },
    );

    testWidgets(
      'without any transaction there is no filter and the AI service is not called',
      (tester) async {
        transactions = [];

        await pumpPage(tester);

        expect(find.byType(SpendingRangeFilter), findsNothing);
        expect(analyses(), isEmpty);
        expect(tester.takeException(), isNull);
      },
    );

    testWidgets(
      'a range with no transaction says there is not enough data and does not call the AI service',
      (tester) async {
        transactions = [_tx(3, _lastMonthDay, amount: 4000)];

        await pumpPage(tester);

        expect(analyses(), isEmpty);
        expect(find.text(_notEnoughDataTitle), findsOneWidget);
        expect(find.text(_errorCardTitle), findsNothing);
      },
    );
  });

  group('time filter', () {
    testWidgets(
      'choosing this week analyses only the transactions of this week and redraws the chart with its seven days',
      (tester) async {
        await pumpPage(tester);

        await _tap(tester, _weekLabel);

        expect(_selectedOption(tester), _weekLabel);
        expect(analyses(), hasLength(2));
        expect(_amountsSent(analyses().last), [1000, 9000]);
        expect(_barHeights(tester), _weekBars({_today: 1000}));
        for (final label in ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN']) {
          expect(find.text(label), findsOneWidget, reason: label);
        }
        expect(find.text('W1'), findsNothing);
        expect(find.text(SpendingRange.week(_now).label), findsOneWidget);
      },
    );

    testWidgets(
      'choosing this month again goes back to the transactions and the bars of the month',
      (tester) async {
        await pumpPage(tester);

        await _tap(tester, _weekLabel);
        await _tap(tester, _monthLabel);

        expect(_selectedOption(tester), _monthLabel);
        expect(analyses(), hasLength(3));
        expect(_amountsSent(analyses().last), [1000, 2000, 9000]);
        expect(
          _barHeights(tester),
          _monthBars({_today: 1000, _monthOnlyDay: 2000}),
        );
      },
    );

    testWidgets('each answer replaces the patterns of the previous range', (
      tester,
    ) async {
      await pumpPage(tester);
      expect(find.text(_foodThenTransportShown), findsOneWidget);

      patterns = [_pattern(_billsThenShopping)];
      await _tap(tester, _weekLabel);

      expect(find.text(_billsThenShoppingShown), findsOneWidget);
      expect(find.text(_foodThenTransportShown), findsNothing);
    });

    testWidgets(
      'tapping the period that is already on screen does not call the AI service again',
      (tester) async {
        await pumpPage(tester);

        await _tap(tester, _monthLabel);

        expect(analyses(), hasLength(1));
      },
    );

    testWidgets(
      'the "So sánh tuần" card keeps comparing the weeks of the month, whatever the chart shows',
      (tester) async {
        await pumpPage(tester);
        final bars = _monthBars({_today: 1000, _monthOnlyDay: 2000});
        var best = 0;
        for (var i = 1; i < bars.length; i++) {
          if (bars[i] > bars[best]) best = i;
        }
        final card = 'Tuần ${best + 1} có chi tiêu cao nhất';
        expect(find.textContaining(card), findsOneWidget);

        await _tap(tester, _weekLabel);

        expect(find.textContaining(card), findsOneWidget);
      },
    );

    testWidgets('a change of the data keeps the period the user chose', (
      tester,
    ) async {
      await pumpPage(tester);
      await _tap(tester, _weekLabel);

      RemoteApiHelper.instance.dataChangedNotifier.value++;
      await tester.pumpAndSettle();

      expect(_selectedOption(tester), _weekLabel);
      expect(analyses(), hasLength(3));
      expect(_amountsSent(analyses().last), [1000, 9000]);
    });
  });

  group('custom range', () {
    testWidgets(
      'analyses the transactions of the picked days, draws bars that fit their number and is marked on screen',
      (tester) async {
        final firstOfLastMonth = _at(_now.year, _now.month - 1, 1);
        DateTimeRange? offered;
        await pumpPage(
          tester,
          pickDateRange: (_, initial) async {
            offered = initial;
            return DateTimeRange(start: firstOfLastMonth, end: _lastMonthDay);
          },
        );

        await _tap(tester, _customLabel);

        expect(
          offered,
          DateTimeRange(
            start: DateTime(_now.year, _now.month, 1),
            end: DateTime(_now.year, _now.month + 1, 0),
          ),
          reason: 'the picker starts from the days on screen',
        );
        expect(_selectedOption(tester), _customLabel);
        expect(analyses(), hasLength(2));
        expect(_amountsSent(analyses().last), [4000]);
        expect(
          find.text(
            SpendingRange.custom(firstOfLastMonth, _lastMonthDay).label,
          ),
          findsOneWidget,
        );
        expect(_barHeights(tester), [
          0.0,
          0.0,
          0.0,
          0.0,
          4000.0,
        ], reason: 'fifteen days make five bars of three days');
      },
    );

    testWidgets('a picker that is closed without a choice changes nothing', (
      tester,
    ) async {
      await pumpPage(tester, pickDateRange: (_, _) async => null);

      await _tap(tester, _customLabel);

      expect(_selectedOption(tester), _monthLabel);
      expect(analyses(), hasLength(1));
    });

    testWidgets('a preset chosen after a custom range replaces it', (
      tester,
    ) async {
      await pumpPage(
        tester,
        pickDateRange: (_, _) async =>
            DateTimeRange(start: _lastMonthDay, end: _lastMonthDay),
      );

      await _tap(tester, _customLabel);
      await _tap(tester, _weekLabel);

      expect(_selectedOption(tester), _weekLabel);
      expect(_amountsSent(analyses().last), [1000, 9000]);
    });

    testWidgets(
      'Tùy chọn opens the real date range picker, and Áp dụng keeps the days it started from',
      (tester) async {
        await pumpPage(tester);

        await _tap(tester, _customLabel);
        expect(find.byType(DateRangePickerDialog), findsOneWidget);

        await _tap(tester, _applyLabel);

        expect(find.byType(DateRangePickerDialog), findsNothing);
        expect(_selectedOption(tester), _customLabel);
        expect(analyses(), hasLength(2));
        expect(_amountsSent(analyses().last), [1000, 2000, 9000]);
        expect(find.text(SpendingRange.month(_now).label), findsOneWidget);
      },
    );

    testWidgets('closing the real date range picker changes nothing', (
      tester,
    ) async {
      await pumpPage(tester);

      await _tap(tester, _customLabel);
      await tester.tap(find.byType(CloseButton));
      await tester.pumpAndSettle();

      expect(find.byType(DateRangePickerDialog), findsNothing);
      expect(_selectedOption(tester), _monthLabel);
      expect(analyses(), hasLength(1));
    });
  });

  group('refresh button', () {
    testWidgets(
      'asks the AI service again for the range on screen and shows the new patterns',
      (tester) async {
        await pumpPage(tester);
        expect(find.text(_foodThenTransportShown), findsOneWidget);

        patterns = [_pattern(_billsThenShopping)];
        await _tap(tester, _refreshLabel);

        expect(analyses(), hasLength(2));
        expect(_amountsSent(analyses().last), [1000, 2000, 9000]);
        expect(find.text(_billsThenShoppingShown), findsOneWidget);
        expect(find.text(_foodThenTransportShown), findsNothing);
      },
    );

    testWidgets('keeps the period the user chose', (tester) async {
      await pumpPage(tester);
      await _tap(tester, _weekLabel);

      await _tap(tester, _refreshLabel);

      expect(_selectedOption(tester), _weekLabel);
      expect(analyses(), hasLength(3));
      expect(_amountsSent(analyses().last), [1000, 9000]);
    });

    testWidgets(
      'cannot be pressed while the analysis runs, and works again once it is over',
      (tester) async {
        final answer = Completer<ResponseBody>();
        backend.on('POST', '/spade/analyze', (_) => answer.future);

        await pumpPageWhileLoading(tester);

        expect(_refreshEnabled(tester), isFalse);
        expect(
          find.descendant(
            of: find.byType(SpendingRangeFilter),
            matching: find.byType(CircularProgressIndicator),
          ),
          findsOneWidget,
        );

        answer.complete(_patternsResponse(patterns));
        await tester.pumpAndSettle();

        expect(_refreshEnabled(tester), isTrue);
        expect(find.byType(CircularProgressIndicator), findsNothing);
        expect(analyses(), hasLength(1));
      },
    );
  });

  group('answers out of order', () {
    late List<Completer<ResponseBody>> gates;

    /// Opens the tab on the month and then chooses the week, both answers of the AI service still pending.
    Future<void> openMonthThenChooseWeek(WidgetTester tester) async {
      gates = [];
      backend.on('POST', '/spade/analyze', (_) {
        final gate = Completer<ResponseBody>();
        gates.add(gate);
        return gate.future;
      });
      await pumpPageWhileLoading(tester);
      expect(gates, hasLength(1), reason: 'the analysis of the month');

      await tester.tap(find.text(_weekLabel));
      await _pumpFrames(tester);
      expect(gates, hasLength(2), reason: 'the analysis of the week');
    }

    testWidgets(
      'an answer that comes after the user changed the filter is dropped',
      (tester) async {
        await openMonthThenChooseWeek(tester);

        gates[1].complete(_patternsResponse([_pattern(_billsThenShopping)]));
        await _pumpFrames(tester);
        expect(find.text(_billsThenShoppingShown), findsOneWidget);
        expect(_refreshEnabled(tester), isTrue);

        gates[0].complete(_patternsResponse([_pattern(_foodThenTransport)]));
        await _pumpFrames(tester);

        expect(find.text(_billsThenShoppingShown), findsOneWidget);
        expect(find.text(_foodThenTransportShown), findsNothing);
        expect(_selectedOption(tester), _weekLabel);
      },
    );

    testWidgets(
      'an old answer neither shows its patterns nor stops the spinner of the newer analysis',
      (tester) async {
        await openMonthThenChooseWeek(tester);

        gates[0].complete(_patternsResponse([_pattern(_foodThenTransport)]));
        await _pumpFrames(tester);

        expect(find.text(_foodThenTransportShown), findsNothing);
        expect(
          _refreshEnabled(tester),
          isFalse,
          reason: 'the analysis of the week is still running',
        );

        gates[1].complete(_patternsResponse([_pattern(_billsThenShopping)]));
        await tester.pumpAndSettle();

        expect(find.text(_billsThenShoppingShown), findsOneWidget);
        expect(_refreshEnabled(tester), isTrue);
      },
    );

    testWidgets('an answer that arrives after the page is gone does nothing', (
      tester,
    ) async {
      final answer = Completer<ResponseBody>();
      backend.on('POST', '/spade/analyze', (_) => answer.future);
      await pumpPageWhileLoading(tester);

      await tester.pumpWidget(const SizedBox());
      answer.complete(_patternsResponse(patterns));
      await _pumpFrames(tester);

      expect(tester.takeException(), isNull);
    });

    testWidgets(
      'a failure that arrives after the page is gone opens no dialog',
      (tester) async {
        final answer = Completer<ResponseBody>();
        backend.on('POST', '/spade/analyze', (_) => answer.future);
        await pumpPageWhileLoading(tester);
        await tester.tap(find.text(_weekLabel));
        await _pumpFrames(tester);

        await tester.pumpWidget(const SizedBox());
        answer.completeError(
          DioException(
            requestOptions: RequestOptions(path: '/spade/analyze'),
            type: DioExceptionType.connectionError,
          ),
        );
        await _pumpFrames(tester);

        expect(tester.takeException(), isNull);
        expect(find.text(_errorTitle), findsNothing);
      },
    );
  });

  group('errors', () {
    testWidgets(
      'a failed background load is shown on the card without a dialog; Làm mới then reports it and can recover',
      (tester) async {
        await withCapturedLogs((logs) async {
          backend.on(
            'POST',
            '/spade/analyze',
            (o) => throw DioException(
              requestOptions: o,
              type: DioExceptionType.connectionError,
            ),
          );

          await pumpPage(tester);

          expect(find.text(_errorCardTitle), findsOneWidget);
          expect(
            find.text(_notEnoughDataTitle),
            findsNothing,
            reason: 'a failure is not a lack of data',
          );
          expect(
            find.text(_dismissLabel),
            findsNothing,
            reason: 'nothing the user asked for failed, so no dialog',
          );
          expect(logs.where((l) => l.contains(_failureLog)), hasLength(1));

          await _tap(tester, _refreshLabel);

          expect(find.text(_errorTitle), findsOneWidget);
          expect(find.text(_networkMessage), findsOneWidget);
          await _tap(tester, _dismissLabel);
          expect(find.text(_errorTitle), findsNothing);
          expect(find.text(_errorCardTitle), findsOneWidget);
          expect(logs.where((l) => l.contains(_failureLog)), hasLength(2));

          backend.on(
            'POST',
            '/spade/analyze',
            (_) => _patternsResponse(patterns),
          );
          await _tap(tester, _refreshLabel);

          expect(find.text(_errorCardTitle), findsNothing);
          expect(find.text(_foodThenTransportShown), findsOneWidget);
          expect(tester.takeException(), isNull);
        });
      },
    );

    testWidgets(
      'a server error on a filter change is reported as a problem of the server, and the old patterns go',
      (tester) async {
        await withCapturedLogs((logs) async {
          await pumpPage(tester);
          expect(find.text(_foodThenTransportShown), findsOneWidget);

          backend.on(
            'POST',
            '/spade/analyze',
            (_) => jsonResponse(503, {'detail': 'down'}),
          );
          await _tap(tester, _weekLabel);

          expect(find.text(_errorTitle), findsOneWidget);
          expect(find.text(_serverMessage), findsOneWidget);
          expect(
            find.text(_foodThenTransportShown),
            findsNothing,
            reason: 'the patterns of the month do not belong to the week',
          );
          expect(_selectedOption(tester), _weekLabel);
          await _tap(tester, _dismissLabel);
          expect(find.text(_errorCardTitle), findsOneWidget);
          expect(logs.where((l) => l.contains(_failureLog)), hasLength(1));
        });
      },
    );

    testWidgets('a request the server refuses is reported as not processed', (
      tester,
    ) async {
      await withCapturedLogs((logs) async {
        await pumpPage(tester);

        backend.on(
          'POST',
          '/spade/analyze',
          (_) => jsonResponse(422, {'detail': 'bad'}),
        );
        await _tap(tester, _refreshLabel);

        expect(find.text(_errorTitle), findsOneWidget);
        expect(find.text(_rejectedMessage), findsOneWidget);
        await _tap(tester, _dismissLabel);
      });
    });

    testWidgets('a failure of the custom range is reported too', (
      tester,
    ) async {
      await withCapturedLogs((logs) async {
        await pumpPage(
          tester,
          pickDateRange: (_, _) async =>
              DateTimeRange(start: _lastMonthDay, end: _lastMonthDay),
        );

        backend.on(
          'POST',
          '/spade/analyze',
          (o) => throw DioException(
            requestOptions: o,
            type: DioExceptionType.receiveTimeout,
          ),
        );
        await _tap(tester, _customLabel);

        expect(find.text(_errorTitle), findsOneWidget);
        expect(find.text(_networkMessage), findsOneWidget);
        expect(_selectedOption(tester), _customLabel);
        await _tap(tester, _dismissLabel);
      });
    });
  });
}
