import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/utils/password_policy.dart';
import 'package:finance_ai_app/screens/profile/account_credentials_screen.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../../helpers/fake_backend.dart';

const String _changePasswordPath = '/api/auth/change-password';
const String _oldPassword = 'OldPass123';
const String _newPassword = 'NewPass456';

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
    // In full mode the app never learns the password of the user (see AppRepository.getCurrentUser).
    backend.on(
      'GET',
      '/api/users/me',
      (_) => okResponse({'id': 1, 'email': 'an@example.com'}),
    );
    SharedPreferences.setMockInitialValues({});
  });

  Future<void> openPasswordForm(WidgetTester tester) async {
    tester.view.physicalSize = const Size(600, 3000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      const MaterialApp(home: AccountCredentialsScreen(userId: 1)),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Mật khẩu'));
    await tester.pumpAndSettle();
  }

  Future<void> fillForm(
    WidgetTester tester, {
    String oldPassword = _oldPassword,
    String newPassword = _newPassword,
    String? confirmPassword,
  }) async {
    await tester.enterText(
      find.widgetWithText(TextField, 'Mật khẩu cũ'),
      oldPassword,
    );
    await tester.enterText(
      find.widgetWithText(TextField, 'Mật khẩu mới'),
      newPassword,
    );
    await tester.enterText(
      find.widgetWithText(TextField, 'Xác nhận mật khẩu mới'),
      confirmPassword ?? newPassword,
    );
  }

  Future<void> tapSave(WidgetTester tester) async {
    await tester.tap(find.text('Lưu thay đổi'));
    await tester.pumpAndSettle();
  }

  Future<void> dismissDialog(WidgetTester tester, String buttonText) async {
    await tester.tap(find.text(buttonText));
    await tester.pumpAndSettle();
  }

  List<RequestOptions> changePasswordCalls() =>
      backend.requests.where((r) => r.path == _changePasswordPath).toList();

  group('the password row', () {
    testWidgets(
      'shows a hidden password although the server never sends it, and opens the form',
      (tester) async {
        await openPasswordForm(tester);

        expect(find.text('********'), findsOneWidget);
        expect(find.widgetWithText(TextField, 'Mật khẩu cũ'), findsOneWidget);
        expect(find.widgetWithText(TextField, 'Mật khẩu mới'), findsOneWidget);
        expect(
          find.widgetWithText(TextField, 'Xác nhận mật khẩu mới'),
          findsOneWidget,
        );
      },
    );
  });

  group('changing the password on the server', () {
    testWidgets(
      'sends the old and the new password, nothing else, then confirms and closes the form',
      (tester) async {
        // The change ends every session of the account; the answer carries the pair this device goes on with.
        backend.on(
          'PUT',
          _changePasswordPath,
          (_) => okResponse({
            'token': 'changed-access',
            'refreshToken': 'changed-refresh',
          }, message: 'Đổi mật khẩu thành công!'),
        );
        await openPasswordForm(tester);
        await fillForm(tester);

        await tapSave(tester);

        final calls = changePasswordCalls();
        expect(calls, hasLength(1));
        expect(calls.single.method, 'PUT');
        expect(calls.single.data, {
          'oldPassword': _oldPassword,
          'newPassword': _newPassword,
        });
        expect(find.text('Đổi mật khẩu thành công'), findsOneWidget);

        await dismissDialog(tester, 'OK');

        expect(find.byType(TextField), findsNothing);
        expect(SessionService.currentUserId, 1);
        expect(SessionService.currentToken, 'changed-access');
        expect(SessionService.currentRefreshToken, 'changed-refresh');
      },
    );

    testWidgets(
      'a wrong old password is shown with the message of the server, and the form keeps what was typed',
      (tester) async {
        backend.on(
          'PUT',
          _changePasswordPath,
          (_) => jsonResponse(400, {
            'code': 400,
            'message': 'Mật khẩu cũ không chính xác',
          }),
        );
        await openPasswordForm(tester);
        await fillForm(tester, oldPassword: 'WrongPass1');

        await tapSave(tester);

        expect(find.text('Đổi mật khẩu thất bại'), findsOneWidget);
        expect(find.text('Mật khẩu cũ không chính xác'), findsOneWidget);

        await dismissDialog(tester, 'Đã hiểu');

        final oldField = tester.widget<TextField>(
          find.widgetWithText(TextField, 'Mật khẩu cũ'),
        );
        expect(oldField.controller!.text, 'WrongPass1');
        expect(SessionService.currentUserId, 1);
      },
    );

    testWidgets(
      'without a connection it says so instead of showing the raw error',
      (tester) async {
        backend.on(
          'PUT',
          _changePasswordPath,
          (options) => throw DioException.connectionError(
            requestOptions: options,
            reason: 'no network',
          ),
        );
        await openPasswordForm(tester);
        await fillForm(tester);

        await tapSave(tester);

        expect(find.text('Đổi mật khẩu thất bại'), findsOneWidget);
        expect(
          find.text(
            'Không thể kết nối máy chủ. Vui lòng kiểm tra mạng và thử lại.',
          ),
          findsOneWidget,
        );
      },
    );

    testWidgets(
      'a 403 (missing or invalid token) asks the user to sign in again',
      (tester) async {
        backend.on(
          'PUT',
          _changePasswordPath,
          (_) => jsonResponse(403, {'error': 'Forbidden'}),
        );
        await openPasswordForm(tester);
        await fillForm(tester);

        await tapSave(tester);

        expect(
          find.text('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.'),
          findsOneWidget,
        );
      },
    );

    testWidgets(
      'an answer that arrives after the screen was left changes nothing and throws nothing',
      (tester) async {
        final answer = Completer<ResponseBody>();
        backend.on('PUT', _changePasswordPath, (_) => answer.future);
        await openPasswordForm(tester);
        await fillForm(tester);

        await tester.tap(find.text('Lưu thay đổi'));
        await tester.pump(const Duration(milliseconds: 100));
        expect(changePasswordCalls(), hasLength(1));

        await tester.pumpWidget(const SizedBox.shrink());
        answer.complete(okResponse(null));
        await tester.pump(const Duration(milliseconds: 100));

        expect(tester.takeException(), isNull);
      },
    );
  });

  group('input that is refused before anything is sent', () {
    final refusals =
        <
          String,
          ({
            String oldPassword,
            String newPassword,
            String confirmPassword,
            String message,
          })
        >{
          'an empty old password': (
            oldPassword: '',
            newPassword: _newPassword,
            confirmPassword: _newPassword,
            message: 'Vui lòng nhập mật khẩu cũ.',
          ),
          'a new password that breaks the policy': (
            oldPassword: _oldPassword,
            newPassword: 'short',
            confirmPassword: 'short',
            message: PasswordPolicy.validate('short')!,
          ),
          'a confirmation that does not match': (
            oldPassword: _oldPassword,
            newPassword: _newPassword,
            confirmPassword: 'Other789x',
            message: 'Mật khẩu mới không khớp.',
          ),
        };

    for (final entry in refusals.entries) {
      testWidgets('${entry.key} is reported and the server is not called', (
        tester,
      ) async {
        await openPasswordForm(tester);
        await fillForm(
          tester,
          oldPassword: entry.value.oldPassword,
          newPassword: entry.value.newPassword,
          confirmPassword: entry.value.confirmPassword,
        );

        await tapSave(tester);

        expect(find.text(entry.value.message), findsOneWidget);
        expect(changePasswordCalls(), isEmpty);
      });
    }
  });
}
