import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/auth/login_page.dart';
import 'package:finance_ai_app/screens/profile/profile_page.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/services/social_auth_service.dart';

import '../../helpers/app_settings_scope.dart';
import '../../helpers/fake_backend.dart';
import '../../helpers/fake_local_store.dart';

const String _logoutPath = '/api/auth/logout';

/// Answers at once. The real [GoogleSignIn] keeps the Future of its first call in a field, and inside the fake async
/// zone of a later test that Future never wakes up, so only the first sign-out of a test file would ever finish.
class _FakeGoogleSignIn implements GoogleSignIn {
  @override
  Future<GoogleSignInAccount?> disconnect() async => null;

  @override
  dynamic noSuchMethod(Invocation invocation) {
    throw UnimplementedError(
      '_FakeGoogleSignIn does not fake ${invocation.memberName}',
    );
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final backend = FakeBackend();

  // The Facebook sign-out is a plain channel call; without a handler it never comes back inside the fake async of testWidgets.
  const facebookChannel = MethodChannel('app.meedu/flutter_facebook_auth');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUpAll(() {
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
    AppRepository.instance.localStore = FakeLocalStore();
    SocialAuthService.instance.googleSignIn = _FakeGoogleSignIn();
    messenger.setMockMethodCallHandler(facebookChannel, (_) async => null);
  });

  tearDownAll(() {
    messenger.setMockMethodCallHandler(facebookChannel, null);
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    backend.reset();
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    await SessionService.setTokens('access-token', 'refresh-token');
    backend.on(
      'GET',
      '/api/users/me',
      (_) => okResponse({'id': 1, 'email': 'an@example.com'}),
    );
    backend.on('GET', '/api/transactions/mine', (_) => okResponse([]));
    SharedPreferences.setMockInitialValues({});
  });

  Future<void> pumpProfile(WidgetTester tester) async {
    tester.view.physicalSize = const Size(600, 3000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    addTearDown(() => AppConfig.mode = AppMode.full);

    // ProfilePage shows the theme and language choices, so it needs the cubits and localizations DonutPayApp provides.
    await tester.pumpWidget(withAppSettings(const ProfilePage()));
    await tester.pumpAndSettle();
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('Đăng xuất'), findsOneWidget);
  }

  Future<void> openConfirmation(WidgetTester tester) async {
    await tester.tap(find.text('Đăng xuất'));
    await tester.pumpAndSettle();
    expect(find.text('Đăng xuất?'), findsOneWidget);
  }

  Future<void> confirmLogout(WidgetTester tester) async {
    await openConfirmation(tester);
    await tester.tap(
      find.descendant(
        of: find.byType(Dialog),
        matching: find.text('Đăng xuất'),
      ),
    );
    // The dialog closes, then the server call, the social sign-out and the local clean-up run before the navigation.
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    await tester.pumpAndSettle();
  }

  void expectSignedOut(WidgetTester tester) {
    expect(find.byType(LoginScreen), findsOneWidget);
    expect(find.byType(ProfilePage), findsNothing);
    expect(SessionService.currentToken, isNull);
    expect(SessionService.currentRefreshToken, isNull);
    expect(SessionService.currentUserId, isNull);
  }

  group('signing out in full mode', () {
    testWidgets(
      'asks the server to revoke the tokens while they are still stored, then clears the session and shows the login screen',
      (tester) async {
        backend.on(
          'POST',
          _logoutPath,
          (_) => okResponse(null, message: 'Đăng xuất thành công!'),
        );
        await pumpProfile(tester);

        await confirmLogout(tester);

        final logout = backend.requests.singleWhere(
          (r) => r.path == _logoutPath,
        );
        expect(logout.method, 'POST');
        expect(logout.headers['Authorization'], 'Bearer access-token');
        expect(logout.data, {'refreshToken': 'refresh-token'});
        expectSignedOut(tester);
      },
    );

    testWidgets(
      'an error of the server (500) still clears the session and shows the login screen',
      (tester) async {
        backend.on(
          'POST',
          _logoutPath,
          (_) => jsonResponse(500, {'code': 500, 'message': 'Lỗi hệ thống'}),
        );
        await pumpProfile(tester);

        await confirmLogout(tester);

        expect(backend.calls, contains('POST $_logoutPath'));
        expectSignedOut(tester);
      },
    );

    testWidgets(
      'no network still clears the session and shows the login screen',
      (tester) async {
        backend.on(
          'POST',
          _logoutPath,
          (options) => throw DioException.connectionError(
            requestOptions: options,
            reason: 'no network',
          ),
        );
        await pumpProfile(tester);

        await confirmLogout(tester);

        expect(backend.calls, contains('POST $_logoutPath'));
        expectSignedOut(tester);
      },
    );

    testWidgets(
      'cancelling the confirmation calls nothing and keeps the session',
      (tester) async {
        await pumpProfile(tester);
        await openConfirmation(tester);

        await tester.tap(find.text('Hủy'));
        await tester.pumpAndSettle();

        expect(backend.calls, isNot(contains('POST $_logoutPath')));
        expect(find.byType(ProfilePage), findsOneWidget);
        expect(SessionService.currentToken, 'access-token');
        expect(SessionService.currentUserId, 1);
      },
    );
  });

  group('signing out in demo mode', () {
    testWidgets(
      'calls no server, only clears the session and shows the login screen',
      (tester) async {
        await pumpProfile(tester);
        AppConfig.mode = AppMode.demo;

        await confirmLogout(tester);

        expect(backend.calls, isNot(contains('POST $_logoutPath')));
        expectSignedOut(tester);
      },
    );
  });
}
