import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/l10n/app_localizations.dart';
import 'package:finance_ai_app/main.dart';
import 'package:finance_ai_app/models/app_language.dart';
import 'package:finance_ai_app/screens/auth/login_page.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../helpers/fake_backend.dart';
import '../helpers/fake_settings_repository.dart';

const String _refreshCall = 'POST /api/auth/refresh';
const String _homeText = 'Trang chủ';
// English on purpose: the text has to follow the language setting, not be written into the interceptor.
const String _sessionEndedText =
    'Your session has ended, for example because your password was changed. Please sign in again.';

ResponseBody _unauthorized() => jsonResponse(401, {
  'code': 401,
  'message': 'Phiên đăng nhập đã hết hiệu lực. Vui lòng đăng nhập lại.',
});

/// Counts the routes pushed on the app's navigator.
class _PushCounter extends NavigatorObserver {
  int pushes = 0;

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) {
    pushes++;
  }
}

void main() {
  final backend = FakeBackend();

  setUpAll(() {
    RemoteApiHelper.instance.init(
      httpClientAdapter: backend,
      settingsRepository: FakeSettingsRepository(language: AppLanguage.english),
    );
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    backend.reset();
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    await SessionService.setTokens('access-token', 'refresh-token');
    // getInstance() hands out one shared Future: made again here, so the clean-up inside the test gets one of its own zone.
    SharedPreferences.setMockInitialValues({});
    backend.on('POST', '/api/auth/refresh', (_) => _unauthorized());
  });

  /// A signed-in app with the global keys of DonutPayApp, which is all the interceptor can reach: no BuildContext.
  Future<_PushCounter> pumpSignedInApp(WidgetTester tester) async {
    final observer = _PushCounter();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        scaffoldMessengerKey: scaffoldMessengerKey,
        navigatorObservers: [observer],
        theme: AppTheme.lightTheme,
        locale: AppLanguage.english.locale,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const Scaffold(body: Text(_homeText)),
      ),
    );
    // The home route itself.
    observer.pushes = 0;
    return observer;
  }

  /// Starts [request] and records how it ends, without waiting: fake time only moves when the test pumps.
  void record(Future<Object?> request, List<Object?> outcomes) {
    request.then<void>(
      (_) => outcomes.add(null),
      onError: (Object error) => outcomes.add(error),
    );
  }

  Future<void> letTheRequestsRun(WidgetTester tester) async {
    // Dio moves every request along with zero-length timers, which only fire when the fake clock moves.
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    await tester.pumpAndSettle();
  }

  Matcher isDioError(int status) => isA<DioException>().having(
    (e) => e.response?.statusCode,
    'status code',
    status,
  );

  testWidgets(
    'requests refused together while the session cannot be renewed: one refresh, one redirect to the login screen, one SnackBar',
    (tester) async {
      final pushes = await pumpSignedInApp(tester);
      backend.on('GET', '/api/budgets', (_) => _unauthorized());
      backend.on(
        'GET',
        '/api/notifications/unread-count',
        (_) => _unauthorized(),
      );

      final outcomes = <Object?>[];
      record(RemoteApiHelper.instance.getBudgets(), outcomes);
      record(RemoteApiHelper.instance.getUnreadNotificationCount(), outcomes);
      record(RemoteApiHelper.instance.getBudgets(), outcomes);
      await letTheRequestsRun(tester);

      // Each waiting request ends with its own 401 instead of hanging.
      expect(outcomes, hasLength(3));
      expect(outcomes, everyElement(isDioError(401)));
      expect(backend.calls.where((c) => c == _refreshCall), hasLength(1));
      expect(pushes.pushes, 1);
      expect(find.byType(LoginScreen), findsOneWidget);
      expect(find.text(_homeText), findsNothing);
      expect(find.text(_sessionEndedText), findsOneWidget);
      expect(SessionService.currentToken, isNull);
      expect(SessionService.currentRefreshToken, isNull);
      expect(SessionService.currentUserId, isNull);

      // Let the SnackBar time out, so nothing is left running.
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
    },
  );

  test('the session-ended text exists in Vietnamese and in English', () {
    final vietnamese = lookupAppLocalizations(
      AppLanguage.vietnamese.locale,
    ).sessionEnded;
    final english = lookupAppLocalizations(
      AppLanguage.english.locale,
    ).sessionEnded;

    expect(english, _sessionEndedText);
    expect(vietnamese, isNotEmpty);
    expect(vietnamese, isNot(english));
  });

  testWidgets(
    'a 401 that arrives after the session has ended neither refreshes nor redirects again',
    (tester) async {
      final pushes = await pumpSignedInApp(tester);
      final lateAnswer = Completer<ResponseBody>();
      backend.on(
        'GET',
        '/api/notifications/unread-count',
        (_) => lateAnswer.future,
      );
      backend.on('GET', '/api/budgets', (_) => _unauthorized());

      final outcomes = <Object?>[];
      // Leaves with the session's token; its answer comes only after the session has been ended.
      record(RemoteApiHelper.instance.getUnreadNotificationCount(), outcomes);
      record(RemoteApiHelper.instance.getBudgets(), outcomes);
      await letTheRequestsRun(tester);
      expect(pushes.pushes, 1);
      expect(outcomes, hasLength(1));

      lateAnswer.complete(_unauthorized());
      await letTheRequestsRun(tester);

      expect(outcomes, hasLength(2));
      expect(outcomes, everyElement(isDioError(401)));
      expect(backend.calls.where((c) => c == _refreshCall), hasLength(1));
      expect(pushes.pushes, 1);
      expect(find.text(_sessionEndedText), findsOneWidget);

      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
    },
  );
}
