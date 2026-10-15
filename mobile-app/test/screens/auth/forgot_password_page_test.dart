import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/screens/auth/forgot_password_page.dart';
import 'package:finance_ai_app/screens/auth/login_page.dart';
import 'package:finance_ai_app/screens/auth/reset_password_page.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/widgets/common/primary_button.dart';

import '../../helpers/app_settings_scope.dart';
import '../../helpers/captured_logs.dart';
import '../../helpers/fake_backend.dart';

const String _forgotPasswordPath = '/api/auth/forgot-password';
const String _resetPasswordPath = '/api/auth/reset-password';
const String _email = 'an@example.com';

const String _forgotLink = 'Quên mật khẩu?';
const String _title = 'Quên mật khẩu';
const String _heading = 'Nhận mã OTP';
const String _description =
    'Nhập email tài khoản của bạn. Chúng tôi sẽ gửi mã OTP để bạn đặt mật khẩu mới.';
const String _emailHint = 'Nhập địa chỉ email';
const String _sendOtp = 'Gửi mã OTP';
const String _sending = 'Đang gửi...';
const String _emailRequired = 'Vui lòng nhập email.';
const String _emailInvalid = 'Email không hợp lệ.';
const String _sendFailed = 'Không gửi được mã OTP';
const String _gotIt = 'Đã hiểu';
const String _serverOnly =
    'Khôi phục mật khẩu cần kết nối máy chủ và không khả dụng ở chế độ demo.';
const String _networkError =
    'Không thể kết nối máy chủ. Vui lòng kiểm tra mạng và thử lại.';
const String _serverError = 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.';
const String _unknownEmail = 'Email không tồn tại trong hệ thống';

void main() {
  final backend = FakeBackend();

  setUpAll(() {
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    // Nobody is signed in on the login screen.
    await SessionService.clearSession();
    // SharedPreferences shares one Future, which must be created inside the test body (see budget_display_test.dart).
    SharedPreferences.setMockInitialValues({});
    backend.reset();
  });

  tearDown(() {
    AppConfig.mode = AppMode.full;
    // The language cubit of withAppSettings sets the default locale of intl.
    Intl.defaultLocale = null;
  });

  /// Shows [home] as the app does; tall, because the test font is much wider than a real one.
  Future<void> open(
    WidgetTester tester, [
    Widget home = const ForgotPasswordScreen(),
  ]) async {
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(withAppSettings(home));
    await tester.pumpAndSettle();
  }

  Finder sendButton() => find.descendant(
    of: find.byType(PrimaryButton),
    matching: find.byType(ElevatedButton),
  );

  /// Types [email] and taps the send button. Dio hands a request on with zero-length timers, so the fake clock has to
  /// move before the request reaches the backend.
  Future<void> send(WidgetTester tester, String email) async {
    await tester.enterText(find.byType(TextFormField), email);
    await tester.tap(sendButton());
    await tester.pump(const Duration(milliseconds: 100));
  }

  /// The OTP request gets its answer only when the test completes the returned Completer.
  Completer<ResponseBody> holdOtpAnswer() {
    final answer = Completer<ResponseBody>();
    backend.on('POST', _forgotPasswordPath, (_) => answer.future);
    return answer;
  }

  void otpSent() => backend.on(
    'POST',
    _forgotPasswordPath,
    (_) => okResponse(null, message: 'Mã OTP đã được gửi đến email: $_email'),
  );

  testWidgets(
    'the "Quên mật khẩu?" link of the login screen opens this screen, whose back button returns to the login screen',
    (tester) async {
      await open(tester, const LoginScreen());

      await tester.tap(find.text(_forgotLink));
      await tester.pumpAndSettle();

      expect(find.byType(ForgotPasswordScreen), findsOneWidget);
      expect(find.byType(AlertDialog), findsNothing, reason: 'no old dialog');
      expect(find.text(_title), findsOneWidget);
      expect(find.text(_heading), findsOneWidget);
      expect(find.text(_description), findsOneWidget);
      expect(find.text('Email'), findsOneWidget);
      expect(find.text(_emailHint), findsOneWidget);
      expect(find.text(_sendOtp), findsOneWidget);

      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();

      expect(find.byType(ForgotPasswordScreen), findsNothing);
      expect(find.byType(LoginScreen), findsOneWidget);
      expect(backend.requests, isEmpty);
      expect(SessionService.currentToken, isNull);
    },
  );

  testWidgets('an empty email is refused under the field and nothing is sent', (
    tester,
  ) async {
    await open(tester);

    await tester.tap(sendButton());
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text(_emailRequired), findsOneWidget);

    await send(tester, '   ');
    expect(find.text(_emailRequired), findsOneWidget);

    expect(backend.requests, isEmpty);
    expect(find.byType(ResetPasswordScreen), findsNothing);
  });

  testWidgets(
    'an email that is not an address is refused under the field and nothing is sent; the error goes once it is fixed',
    (tester) async {
      await open(tester);

      await send(tester, 'an@example');
      expect(find.text(_emailInvalid), findsOneWidget);
      expect(backend.requests, isEmpty);

      await tester.enterText(find.byType(TextFormField), _email);
      await tester.pump();

      expect(find.text(_emailInvalid), findsNothing);
      expect(backend.requests, isEmpty, reason: 'typing sends nothing');
    },
  );

  testWidgets(
    'a valid email is trimmed, sent once as the only query parameter, and opens the reset screen for it',
    (tester) async {
      otpSent();
      await open(tester);

      await send(tester, '  An.Nguyen@Example.com  ');
      await tester.pumpAndSettle();

      expect(backend.calls, ['POST $_forgotPasswordPath']);
      final request = backend.requests.single;
      expect(request.queryParameters, {'email': 'An.Nguyen@Example.com'});
      expect(request.data, isNull);
      expect(
        tester
            .widget<ResetPasswordScreen>(find.byType(ResetPasswordScreen))
            .email,
        'An.Nguyen@Example.com',
        reason: 'trimmed, with the case the user typed',
      );
      expect(SessionService.currentToken, isNull);
    },
  );

  testWidgets(
    'while the OTP is on its way the button is off and says it is sending, and more taps send nothing',
    (tester) async {
      final answer = holdOtpAnswer();
      await open(tester);

      await tester.enterText(find.byType(TextFormField), _email);
      // Two taps in the same frame: the second one comes before the button is rebuilt as disabled.
      await tester.tap(sendButton());
      await tester.tap(sendButton());
      await tester.pump(const Duration(milliseconds: 100));

      expect(backend.requests, hasLength(1));
      expect(tester.widget<ElevatedButton>(sendButton()).onPressed, isNull);
      expect(find.text(_sending), findsOneWidget);

      await tester.tap(sendButton());
      await tester.pump(const Duration(milliseconds: 100));
      expect(backend.requests, hasLength(1));

      answer.complete(okResponse(null));
      await tester.pumpAndSettle();

      expect(find.byType(ResetPasswordScreen), findsOneWidget);
      expect(backend.requests, hasLength(1));
    },
  );

  testWidgets(
    'a refusal shows the server message; the screen stays with the email, ready to try again, and the log keeps only the status',
    (tester) async {
      backend.on(
        'POST',
        _forgotPasswordPath,
        (_) => jsonResponse(400, {'code': 400, 'message': _unknownEmail}),
      );
      await open(tester);

      await withCapturedLogs((logs) async {
        await send(tester, _email);
        await tester.pumpAndSettle();

        expect(logs, ['PasswordRecovery: sendOtp failed (400).']);
      });

      expect(find.text(_sendFailed), findsOneWidget);
      expect(find.text(_unknownEmail), findsOneWidget);

      await tester.tap(find.text(_gotIt));
      await tester.pumpAndSettle();

      expect(find.byType(ResetPasswordScreen), findsNothing);
      expect(find.text(_email), findsOneWidget, reason: 'the field keeps it');
      expect(tester.widget<ElevatedButton>(sendButton()).onPressed, isNotNull);
      expect(find.text(_sendOtp), findsOneWidget);
    },
  );

  for (final (failure, answer, expected) in [
    (
      'a server failure',
      (RequestOptions _) => jsonResponse(500, {
        'code': 500,
        'message': 'Hệ thống sự cố: java.lang.NullPointerException',
      }),
      _serverError,
    ),
    (
      'no connection',
      (RequestOptions _) =>
          throw const SocketException('Failed host lookup: api.ledgermind.dev'),
      _networkError,
    ),
  ]) {
    testWidgets(
      '$failure shows a fixed text, never the raw error, the address or the URL',
      (tester) async {
        backend.on('POST', _forgotPasswordPath, answer);
        await open(tester);

        await withCapturedLogs((logs) async {
          await send(tester, _email);
          await tester.pumpAndSettle();

          expect(logs, hasLength(1));
          expect(logs.single, isNot(contains(_email)));
        });

        expect(find.text(_sendFailed), findsOneWidget);
        expect(find.text(expected), findsOneWidget);
        expect(find.textContaining('Exception'), findsNothing);
        expect(find.textContaining(_forgotPasswordPath), findsNothing);
        expect(find.byType(ResetPasswordScreen), findsNothing);
      },
    );
  }

  testWidgets(
    'without a server (demo mode) nothing is sent: the user is told that recovery needs one',
    (tester) async {
      AppConfig.mode = AppMode.demo;
      await open(tester);

      await send(tester, _email);
      await tester.pumpAndSettle();

      expect(find.text(_serverOnly), findsOneWidget);
      expect(backend.requests, isEmpty);

      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();

      expect(find.byType(ForgotPasswordScreen), findsOneWidget);
      expect(find.byType(ResetPasswordScreen), findsNothing);
    },
  );

  for (final (outcome, answer) in [
    ('arrives', () => okResponse(null)),
    (
      'is refused',
      () => jsonResponse(400, {'code': 400, 'message': _unknownEmail}),
    ),
  ]) {
    testWidgets(
      'the screen is closed before the OTP answer $outcome: no setState, no dialog, no navigation afterwards',
      (tester) async {
        final pending = holdOtpAnswer();
        await open(tester);
        await send(tester, _email);
        expect(backend.requests, hasLength(1));

        await tester.pumpWidget(const SizedBox());
        await withCapturedLogs((_) async {
          pending.complete(answer());
          await tester.pumpAndSettle();
        });

        expect(tester.takeException(), isNull);
        expect(find.byType(ResetPasswordScreen), findsNothing);
        expect(find.byType(Dialog), findsNothing);
      },
    );
  }

  testWidgets(
    'the whole flow: login link, OTP sent, new password set, then a new login screen alone, with nobody signed in',
    (tester) async {
      otpSent();
      backend.on(
        'POST',
        _resetPasswordPath,
        (_) => okResponse(
          null,
          message: 'Đổi mật khẩu thành công! Vui lòng đăng nhập lại.',
        ),
      );
      await open(tester, const LoginScreen());

      await tester.tap(find.text(_forgotLink));
      await tester.pumpAndSettle();
      await send(tester, ' $_email ');
      await tester.pumpAndSettle();
      expect(find.byType(ResetPasswordScreen), findsOneWidget);

      final fields = find.byType(TextFormField);
      await tester.enterText(fields.at(0), '123456');
      await tester.enterText(fields.at(1), 'NewPass456');
      await tester.enterText(fields.at(2), 'NewPass456');
      await tester.tap(
        find.descendant(
          of: find.byType(PrimaryButton),
          matching: find.byType(ElevatedButton),
        ),
      );
      await tester.pump(const Duration(milliseconds: 100));
      await tester.pumpAndSettle();

      expect(find.text('Đặt lại mật khẩu thành công'), findsOneWidget);
      await tester.tap(find.text('Về trang đăng nhập'));
      await tester.pumpAndSettle();

      expect(find.byType(LoginScreen), findsOneWidget);
      expect(
        find.byType(ForgotPasswordScreen, skipOffstage: false),
        findsNothing,
      );
      expect(
        find.byType(ResetPasswordScreen, skipOffstage: false),
        findsNothing,
      );
      expect(
        tester.state<NavigatorState>(find.byType(Navigator)).canPop(),
        isFalse,
      );
      expect(backend.calls, [
        'POST $_forgotPasswordPath',
        'POST $_resetPasswordPath',
      ]);
      expect(backend.requests.first.queryParameters, {'email': _email});
      expect(backend.requests.last.data, {
        'email': _email,
        'otp': '123456',
        'newPassword': 'NewPass456',
      });
      expect(SessionService.currentUserId, isNull);
      expect(SessionService.currentToken, isNull);
      expect(SessionService.currentRefreshToken, isNull);
    },
  );
}
