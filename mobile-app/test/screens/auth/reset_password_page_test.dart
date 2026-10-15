import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/utils/password_policy.dart';
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
const String _otp = '123456';
const String _password = 'NewPass456';

// "an" is a name of two characters, so only its first one shows.
const String _description =
    'Nhập mã OTP đã được gửi tới a***@example.com, sau đó đặt mật khẩu mới.';
const String _otpRequired = 'Vui lòng nhập mã OTP.';
const String _otpInvalid = 'Mã OTP phải gồm đúng 6 chữ số.';
const String _passwordRequired = 'Vui lòng nhập mật khẩu mới.';
const String _passwordRules =
    'Từ 8 đến 72 ký tự, gồm ít nhất một chữ cái và một chữ số.';
const String _confirmationRequired = 'Vui lòng xác nhận mật khẩu mới.';
const String _mismatch = 'Mật khẩu xác nhận không khớp.';
const String _submitting = 'Đang xử lý...';
const String _resend = 'Gửi lại mã OTP';
const String _sending = 'Đang gửi...';
const String _resent = 'Đã gửi lại mã OTP. Vui lòng kiểm tra email.';
const String _successTitle = 'Đặt lại mật khẩu thành công';
const String _successMessage =
    'Mật khẩu đã được thay đổi. Vui lòng đăng nhập bằng mật khẩu mới.';
const String _backToLogin = 'Về trang đăng nhập';
const String _resetFailed = 'Đặt lại mật khẩu thất bại';
const String _sendFailed = 'Không gửi được mã OTP';
const String _gotIt = 'Đã hiểu';
const String _serverError = 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.';
const String _wrongOtp = 'Mã OTP không chính xác. Bạn còn 2 lần thử.';

String _resendIn(int seconds) => 'Gửi lại mã sau $seconds giây';

/// Opens the reset screen on top of itself, so that the screen has a back button.
class _Opener extends StatelessWidget {
  const _Opener();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: TextButton(
          onPressed: () => Navigator.of(context).push(
            MaterialPageRoute(
              builder: (_) => const ResetPasswordScreen(email: _email),
            ),
          ),
          child: const Text('open'),
        ),
      ),
    );
  }
}

void main() {
  final backend = FakeBackend();

  setUpAll(() {
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    // Nobody is signed in during a password recovery.
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

  /// Shows the reset screen for [_email] as the app does; tall by default, because the test font is much wider than a
  /// real one.
  Future<void> open(
    WidgetTester tester, {
    Size size = const Size(800, 2000),
    Widget home = const ResetPasswordScreen(email: _email),
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(withAppSettings(home));
    await tester.pumpAndSettle();
  }

  /// 0 is the OTP, 1 the new password, 2 its confirmation.
  Finder field(int index) => find.byType(TextFormField).at(index);

  String textOf(WidgetTester tester, int index) =>
      tester.widget<TextFormField>(field(index)).controller!.text;

  /// The error shown under the field, as the decorator draws it.
  String? errorOf(WidgetTester tester, int index) => tester
      .widget<InputDecorator>(
        find.descendant(
          of: field(index),
          matching: find.byType(InputDecorator),
        ),
      )
      .decoration
      .errorText;

  Finder resetButton() => find.descendant(
    of: find.byType(PrimaryButton),
    matching: find.byType(ElevatedButton),
  );

  Finder resendButton() => find.byType(TextButton);

  VoidCallback? onPressedOf(WidgetTester tester, Finder button) =>
      tester.widget<ButtonStyleButton>(button).onPressed;

  Future<void> fill(
    WidgetTester tester, {
    String otp = _otp,
    String password = _password,
    String? confirmation,
  }) async {
    await tester.enterText(field(0), otp);
    await tester.enterText(field(1), password);
    await tester.enterText(field(2), confirmation ?? password);
  }

  /// Taps [button]. Dio hands a request on with zero-length timers, so the fake clock has to move before the request
  /// reaches the backend.
  Future<void> tapAndSend(WidgetTester tester, Finder button) async {
    await tester.tap(button);
    await tester.pump(const Duration(milliseconds: 100));
  }

  /// The request to [path] gets its answer only when the test completes the returned Completer.
  Completer<ResponseBody> holdAnswer(String path) {
    final answer = Completer<ResponseBody>();
    backend.on('POST', path, (_) => answer.future);
    return answer;
  }

  testWidgets(
    'shows the address partly hidden, the three fields, the password rules, the countdown and the reset button',
    (tester) async {
      await open(tester);

      expect(
        find.text('Đặt lại mật khẩu'),
        findsNWidgets(2),
        reason: 'the title and the button',
      );
      expect(find.text('Tạo mật khẩu mới'), findsOneWidget);
      expect(find.text(_description), findsOneWidget);
      expect(find.textContaining(_email), findsNothing);
      expect(find.text('Mã OTP'), findsOneWidget);
      expect(find.text('Mã gồm 6 chữ số'), findsOneWidget);
      expect(find.text('Mật khẩu mới'), findsOneWidget);
      expect(find.text(_passwordRules), findsOneWidget);
      expect(find.text('Xác nhận mật khẩu mới'), findsOneWidget);
      expect(find.text(_resendIn(60)), findsOneWidget);
      expect(onPressedOf(tester, resendButton()), isNull);
      expect(onPressedOf(tester, resetButton()), isNotNull);

      final editables = tester
          .widgetList<EditableText>(find.byType(EditableText))
          .toList();
      expect(editables[0].keyboardType, TextInputType.number);
      expect(editables[1].obscureText, isTrue);
      expect(editables[2].obscureText, isTrue);
      expect(backend.requests, isEmpty);
    },
  );

  testWidgets('an empty OTP is refused under its field and nothing is sent', (
    tester,
  ) async {
    await open(tester);

    await fill(tester, otp: '');
    await tapAndSend(tester, resetButton());

    expect(errorOf(tester, 0), _otpRequired);
    expect(backend.requests, isEmpty);
  });

  testWidgets(
    'letters never reach the OTP field, and a code that is not all digits is refused',
    (tester) async {
      await open(tester);

      await fill(tester, otp: '12ab56');
      expect(textOf(tester, 0), '1256');
      await tapAndSend(tester, resetButton());

      expect(errorOf(tester, 0), _otpInvalid);
      expect(backend.requests, isEmpty);
    },
  );

  testWidgets(
    'an OTP of the wrong length is refused, and a longer paste is cut to six digits',
    (tester) async {
      await open(tester);

      await fill(tester, otp: '12345');
      await tapAndSend(tester, resetButton());
      expect(errorOf(tester, 0), _otpInvalid);
      expect(backend.requests, isEmpty);

      await tester.enterText(field(0), '1234567');
      await tester.pump();

      expect(textOf(tester, 0), '123456');
      expect(errorOf(tester, 0), isNull);
      expect(backend.requests, isEmpty);
    },
  );

  testWidgets(
    'a new password that breaks the PasswordPolicy is refused with the rules, and nothing is sent',
    (tester) async {
      await open(tester);

      await fill(tester, password: '');
      await tapAndSend(tester, resetButton());
      expect(errorOf(tester, 1), _passwordRequired);

      for (final weak in ['abcdefgh', '12345678', 'Ab1', 'A1' * 37]) {
        expect(PasswordPolicy.validate(weak), isNotNull, reason: weak);
        await fill(tester, password: weak);
        await tapAndSend(tester, resetButton());
        expect(errorOf(tester, 1), _passwordRules, reason: weak);
      }
      expect(backend.requests, isEmpty);
    },
  );

  testWidgets(
    'a confirmation that differs or is missing is refused, and nothing is sent',
    (tester) async {
      await open(tester);

      await fill(tester, confirmation: 'NewPass457');
      await tapAndSend(tester, resetButton());
      expect(errorOf(tester, 2), _mismatch);

      await tester.enterText(field(2), '');
      await tester.pump();
      expect(errorOf(tester, 2), _confirmationRequired);

      expect(backend.requests, isEmpty);
    },
  );

  testWidgets(
    'a valid form sends the email, the OTP and the new password once, in the body',
    (tester) async {
      backend.on('POST', _resetPasswordPath, (_) => okResponse(null));
      await open(tester);

      await fill(tester);
      await tapAndSend(tester, resetButton());

      expect(backend.calls, ['POST $_resetPasswordPath']);
      final request = backend.requests.single;
      expect(request.data, {
        'email': _email,
        'otp': _otp,
        'newPassword': _password,
      });
      expect(request.queryParameters, isEmpty);
      for (var i = 0; i < 3; i++) {
        expect(errorOf(tester, i), isNull);
      }
    },
  );

  testWidgets(
    'while the reset is on its way both buttons are off, the reset says it is working, and more taps send nothing',
    (tester) async {
      final answer = holdAnswer(_resetPasswordPath);
      await open(tester);
      // By now resending would be allowed again.
      await tester.pump(ResetPasswordScreen.resendCooldown);
      await fill(tester);

      // Two taps in the same frame: the second one comes before the button is rebuilt as disabled.
      await tester.tap(resetButton());
      await tester.tap(resetButton());
      await tester.pump(const Duration(milliseconds: 100));

      expect(backend.requests, hasLength(1));
      expect(find.text(_submitting), findsOneWidget);
      expect(onPressedOf(tester, resetButton()), isNull);
      expect(
        onPressedOf(tester, resendButton()),
        isNull,
        reason: 'a resend would replace the OTP the reset uses',
      );

      await tapAndSend(tester, resetButton());
      await tapAndSend(tester, resendButton());
      expect(backend.requests, hasLength(1));

      answer.complete(okResponse(null));
      await tester.pumpAndSettle();

      expect(find.text(_successTitle), findsOneWidget);
      expect(backend.requests, hasLength(1));
    },
  );

  testWidgets(
    'a refusal shows the server message, keeps what was typed and stays on the screen; the log keeps no secret',
    (tester) async {
      backend.on(
        'POST',
        _resetPasswordPath,
        (_) => jsonResponse(400, {'code': 400, 'message': _wrongOtp}),
      );
      await open(tester);
      await fill(tester);

      await withCapturedLogs((logs) async {
        await tapAndSend(tester, resetButton());
        await tester.pumpAndSettle();

        expect(logs, ['PasswordRecovery: resetPassword failed (400).']);
      });

      expect(find.text(_resetFailed), findsOneWidget);
      expect(find.text(_wrongOtp), findsOneWidget);

      await tester.tap(find.text(_gotIt));
      await tester.pumpAndSettle();

      expect(find.byType(ResetPasswordScreen), findsOneWidget);
      expect(find.byType(LoginScreen), findsNothing);
      expect(
        [for (var i = 0; i < 3; i++) textOf(tester, i)],
        [_otp, _password, _password],
      );
      expect(onPressedOf(tester, resetButton()), isNotNull);
    },
  );

  testWidgets(
    'success opens no session, shows the success dialog, and only its button leads to a new login screen alone',
    (tester) async {
      // Even an answer that carried tokens would sign nobody in.
      backend.on(
        'POST',
        _resetPasswordPath,
        (_) => okResponse({
          'token': 'access',
          'refreshToken': 'refresh',
          'userId': 7,
        }, message: 'Đổi mật khẩu thành công! Vui lòng đăng nhập lại.'),
      );
      await open(tester);

      await fill(tester);
      await tapAndSend(tester, resetButton());
      await tester.pumpAndSettle();

      expect(find.text(_successTitle), findsOneWidget);
      expect(find.text(_successMessage), findsOneWidget);
      expect(
        find.byType(LoginScreen),
        findsNothing,
        reason: 'not before the dialog is closed',
      );
      expect(SessionService.currentUserId, isNull);
      expect(SessionService.currentToken, isNull);
      expect(SessionService.currentRefreshToken, isNull);

      await tester.tap(find.text(_backToLogin));
      await tester.pumpAndSettle();

      expect(find.byType(LoginScreen), findsOneWidget);
      expect(
        find.byType(ResetPasswordScreen, skipOffstage: false),
        findsNothing,
      );
      expect(
        tester.state<NavigatorState>(find.byType(Navigator)).canPop(),
        isFalse,
      );
      expect(SessionService.currentToken, isNull);
    },
  );

  testWidgets(
    'the back button does nothing while the reset is on its way, so its outcome is never missed',
    (tester) async {
      final answer = holdAnswer(_resetPasswordPath);
      await open(tester, home: const _Opener());
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await fill(tester);
      await tapAndSend(tester, resetButton());
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();

      expect(find.byType(ResetPasswordScreen), findsOneWidget);

      answer.complete(okResponse(null));
      await tester.pumpAndSettle();

      expect(find.text(_successTitle), findsOneWidget);
    },
  );

  testWidgets(
    'resend counts down from 60 s, then sends a new OTP to the same address and counts down again; nothing goes out meanwhile',
    (tester) async {
      backend.on('POST', _forgotPasswordPath, (_) => okResponse(null));
      await open(tester);

      expect(find.text(_resendIn(60)), findsOneWidget);
      await tapAndSend(tester, resendButton());
      await tester.pump(const Duration(milliseconds: 900));
      expect(find.text(_resendIn(59)), findsOneWidget);
      expect(backend.requests, isEmpty);

      await tester.pump(const Duration(seconds: 59));
      expect(find.text(_resend), findsOneWidget);
      expect(onPressedOf(tester, resendButton()), isNotNull);

      await tapAndSend(tester, resendButton());
      await tester.pump();

      expect(backend.calls, ['POST $_forgotPasswordPath']);
      final request = backend.requests.single;
      expect(request.queryParameters, {'email': _email});
      expect(request.data, isNull);
      expect(find.text(_resent), findsOneWidget);
      expect(find.text(_resendIn(60)), findsOneWidget);
      expect(onPressedOf(tester, resendButton()), isNull);

      await tapAndSend(tester, resendButton());
      expect(backend.requests, hasLength(1));
    },
  );

  testWidgets(
    'while a resend is on its way it says so, and the reset is off too',
    (tester) async {
      final answer = holdAnswer(_forgotPasswordPath);
      await open(tester);
      await tester.pump(ResetPasswordScreen.resendCooldown);
      await fill(tester);

      await tapAndSend(tester, resendButton());

      expect(find.text(_sending), findsOneWidget);
      expect(onPressedOf(tester, resendButton()), isNull);
      expect(onPressedOf(tester, resetButton()), isNull);
      await tapAndSend(tester, resetButton());
      expect(backend.calls, ['POST $_forgotPasswordPath']);

      answer.complete(okResponse(null));
      await tester.pump(const Duration(milliseconds: 100));
      await tester.pump();

      expect(onPressedOf(tester, resetButton()), isNotNull);
      expect(find.text(_resendIn(60)), findsOneWidget);
    },
  );

  testWidgets(
    'a failed resend shows a fixed text, keeps what was typed and may be tried again at once',
    (tester) async {
      backend.on(
        'POST',
        _forgotPasswordPath,
        (_) => jsonResponse(500, {
          'code': 500,
          'message':
              'Hệ thống sự cố: MailSendException: Mail server connection failed',
        }),
      );
      await open(tester);
      await fill(tester);
      await tester.pump(ResetPasswordScreen.resendCooldown);

      await withCapturedLogs((logs) async {
        await tapAndSend(tester, resendButton());
        await tester.pumpAndSettle();

        expect(logs, ['PasswordRecovery: sendOtp failed (500).']);
      });

      expect(find.text(_sendFailed), findsOneWidget);
      expect(find.text(_serverError), findsOneWidget);
      expect(find.textContaining('MailSendException'), findsNothing);

      await tester.tap(find.text(_gotIt));
      await tester.pumpAndSettle();

      expect(
        [for (var i = 0; i < 3; i++) textOf(tester, i)],
        [_otp, _password, _password],
      );
      for (var i = 0; i < 3; i++) {
        expect(
          errorOf(tester, i),
          isNull,
          reason: 'the form was neither checked nor cleared',
        );
      }
      expect(find.text(_resend), findsOneWidget);
      expect(onPressedOf(tester, resendButton()), isNotNull);
    },
  );

  testWidgets(
    'the countdown stops with the screen: no timer is left behind and nothing ticks on a disposed notifier',
    (tester) async {
      await open(tester);
      await tester.pump(const Duration(seconds: 5));
      expect(find.text(_resendIn(55)), findsOneWidget);

      await tester.pumpWidget(const SizedBox());
      await tester.pump(ResetPasswordScreen.resendCooldown);

      // testWidgets itself also fails a test that leaves a Timer running once the widget tree is gone.
      expect(tester.takeException(), isNull);
    },
  );

  for (final (name, path) in [
    ('the reset', _resetPasswordPath),
    ('a resend', _forgotPasswordPath),
  ]) {
    for (final (outcome, answer) in [
      ('arrives', () => okResponse(null)),
      (
        'is refused',
        () => jsonResponse(400, {'code': 400, 'message': _wrongOtp}),
      ),
    ]) {
      testWidgets(
        'the screen is closed before the answer to $name $outcome: no setState, no dialog, no navigation afterwards',
        (tester) async {
          final pending = holdAnswer(path);
          await open(tester);
          await fill(tester);
          if (path == _forgotPasswordPath) {
            await tester.pump(ResetPasswordScreen.resendCooldown);
            await tapAndSend(tester, resendButton());
          } else {
            await tapAndSend(tester, resetButton());
          }
          expect(backend.calls, ['POST $path']);

          await tester.pumpWidget(const SizedBox());
          await withCapturedLogs((_) async {
            pending.complete(answer());
            await tester.pumpAndSettle();
          });

          expect(tester.takeException(), isNull);
          expect(find.byType(LoginScreen), findsNothing);
          expect(find.byType(Dialog), findsNothing);
        },
      );
    }
  }

  testWidgets(
    'on a small phone the form scrolls without overflowing and the reset button can be reached',
    (tester) async {
      await open(tester, size: const Size(320, 480));
      expect(tester.takeException(), isNull);

      await tester.ensureVisible(resetButton());
      await tester.pumpAndSettle();
      await tapAndSend(tester, resetButton());

      expect(errorOf(tester, 0), _otpRequired);
      expect(backend.requests, isEmpty);
    },
  );

  testWidgets('each eye button shows or hides its own password only', (
    tester,
  ) async {
    await open(tester);

    expect(find.byTooltip('Hiện mật khẩu'), findsNWidgets(2));
    await tester.tap(find.byTooltip('Hiện mật khẩu').first);
    await tester.pump();

    final editables = tester
        .widgetList<EditableText>(find.byType(EditableText))
        .toList();
    expect(editables[1].obscureText, isFalse);
    expect(editables[2].obscureText, isTrue);
    expect(find.byTooltip('Ẩn mật khẩu'), findsOneWidget);
    expect(find.byTooltip('Hiện mật khẩu'), findsOneWidget);
  });
}
