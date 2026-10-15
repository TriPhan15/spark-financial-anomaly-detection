import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/dashboard/dashboard_page.dart';
import 'package:finance_ai_app/screens/notifications/notification_screen.dart';
import 'package:finance_ai_app/services/budget_alert_tracker.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/widgets/budget/budget_alert_presenter.dart';
import 'package:finance_ai_app/widgets/notifications/notification_bell.dart';

import '../../helpers/app_settings_scope.dart';
import '../../helpers/captured_logs.dart';
import '../../helpers/fake_backend.dart';
import '../../helpers/fake_local_store.dart';

const String _unreadCount = '/api/notifications/unread-count';
const String _list = '/api/notifications';

void main() {
  final backend = FakeBackend();
  var unread = 0;

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
    // The home screen also reads the transactions waiting to be sent; the fake keeps it away from a real database.
    AppRepository.instance.localStore = FakeLocalStore();
  });

  setUp(() async {
    // SharedPreferences shares one Future that must be created inside the test body (see budget_display_test.dart).
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    SharedPreferences.setMockInitialValues({});
    BudgetAlertTracker.instance.resetMemory();
    BudgetAlertPresenter.resetQueue();

    backend.reset();
    unread = 0;
    backend.on(
      'GET',
      '/api/users/me',
      (_) => okResponse({'id': 1, 'email': 'an@example.com'}),
    );
    backend.on('GET', '/api/transactions/mine', (_) => okResponse([]));
    backend.on('GET', '/api/budgets', (_) => okResponse([]));
    backend.on('GET', _unreadCount, (_) => okResponse(unread));
    backend.on(
      'GET',
      _list,
      (_) => okResponse({
        'content': [],
        'page': 0,
        'size': 20,
        'totalElements': 0,
        'totalPages': 0,
        'hasNext': false,
      }),
    );
  });

  // The language cubit of withAppSettings sets the default locale of intl.
  tearDown(() => Intl.defaultLocale = null);

  Future<void> showDashboard(WidgetTester tester) async {
    // Tall and wide, because the test font is much wider than a real one and the rows would overflow at phone width.
    tester.view.physicalSize = const Size(600, 3000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      withAppSettings(const Scaffold(body: DashboardPage())),
    );
    await tester.pumpAndSettle();
  }

  int countRequests() =>
      backend.calls.where((call) => call == 'GET $_unreadCount').length;

  /// What the engine sends when the app goes to the background and comes back.
  Future<void> leaveAndComeBack(WidgetTester tester) async {
    for (final state in [AppLifecycleState.paused, AppLifecycleState.resumed]) {
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        SystemChannels.lifecycle.name,
        SystemChannels.lifecycle.codec.encodeMessage(state.toString()),
        (_) {},
      );
    }
  }

  Badge badge(WidgetTester tester) => tester.widget<Badge>(find.byType(Badge));

  Finder badgeShowing(String label) =>
      find.descendant(of: find.byType(Badge), matching: find.text(label));

  testWidgets(
    'the bell shows the unread count of the server as soon as the page loads, asked without a user id',
    (tester) async {
      unread = 3;

      await showDashboard(tester);

      expect(find.byType(NotificationBell), findsOneWidget);
      expect(badgeShowing('3'), findsOneWidget);
      expect(countRequests(), 1);
      final request = backend.requests.singleWhere(
        (request) => request.path == _unreadCount,
      );
      expect(request.uri.toString(), isNot(contains('userId')));
      expect(request.data, isNull);
    },
  );

  testWidgets('above 99 the bell shows 99+, at 0 it shows no badge', (
    tester,
  ) async {
    unread = 150;
    await showDashboard(tester);
    expect(badgeShowing('99+'), findsOneWidget);

    unread = 0;
    await leaveAndComeBack(tester);
    await tester.pumpAndSettle();

    expect(badge(tester).isLabelVisible, isFalse);
  });

  testWidgets('coming back to the app asks again and updates the bell', (
    tester,
  ) async {
    unread = 1;
    await showDashboard(tester);

    unread = 4;
    await leaveAndComeBack(tester);
    await tester.pumpAndSettle();

    expect(countRequests(), 2);
    expect(badgeShowing('4'), findsOneWidget);
  });

  testWidgets(
    'the bell opens the notification screen, and coming back from it asks again',
    (tester) async {
      unread = 2;
      await showDashboard(tester);

      await tester.tap(find.byType(NotificationBell));
      await tester.pumpAndSettle();
      expect(find.byType(NotificationScreen), findsOneWidget);
      expect(countRequests(), 1, reason: 'nothing is polled meanwhile');

      unread = 0;
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();

      expect(find.byType(NotificationScreen), findsNothing);
      expect(countRequests(), 2);
      expect(badge(tester).isLabelVisible, isFalse);
    },
  );

  testWidgets('pulling the home screen down asks again', (tester) async {
    unread = 1;
    await showDashboard(tester);

    unread = 5;
    // The indicator arms after a pull of a quarter of the view, which is 3000 px tall here.
    await tester.fling(find.text('Tiện ích'), const Offset(0, 1200), 1000);
    await tester.pumpAndSettle();

    expect(countRequests(), 2);
    expect(badgeShowing('5'), findsOneWidget);
  });

  testWidgets(
    'when the count cannot be loaded the bell keeps its last number',
    (tester) async {
      unread = 3;
      await showDashboard(tester);
      backend.on('GET', _unreadCount, (_) => jsonResponse(500, {'code': 500}));

      await withCapturedLogs((logs) async {
        await leaveAndComeBack(tester);
        await tester.pumpAndSettle();

        expect(
          logs.where((log) => log.startsWith('Lỗi tải số thông báo chưa đọc')),
          hasLength(1),
        );
      });
      expect(badgeShowing('3'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'when two answers cross, the bell shows the newer request, not the one that arrived last',
    (tester) async {
      await showDashboard(tester);
      final answers = <Completer<ResponseBody>>[];
      backend.on('GET', _unreadCount, (_) {
        final answer = Completer<ResponseBody>();
        answers.add(answer);
        return answer.future;
      });

      await leaveAndComeBack(tester);
      await tester.pump(const Duration(milliseconds: 100));
      await leaveAndComeBack(tester);
      await tester.pump(const Duration(milliseconds: 100));
      expect(answers, hasLength(2));

      answers[1].complete(okResponse(5));
      await tester.pump(const Duration(milliseconds: 100));
      answers[0].complete(okResponse(9));
      await tester.pumpAndSettle();

      expect(badgeShowing('5'), findsOneWidget);
      expect(badgeShowing('9'), findsNothing);
    },
  );

  testWidgets(
    'once the page is gone, coming back to the app asks for nothing and a late answer touches nothing',
    (tester) async {
      await showDashboard(tester);
      final lateAnswer = Completer<ResponseBody>();
      backend.on('GET', _unreadCount, (_) => lateAnswer.future);
      await leaveAndComeBack(tester);
      await tester.pump(const Duration(milliseconds: 100));
      final requestsBefore = countRequests();

      await tester.pumpWidget(const SizedBox());
      lateAnswer.complete(okResponse(7));
      await leaveAndComeBack(tester);
      await tester.pumpAndSettle();

      expect(countRequests(), requestsBefore);
      expect(tester.takeException(), isNull);
    },
  );
}
