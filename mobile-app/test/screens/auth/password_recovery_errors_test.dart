import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/l10n/app_localizations.dart';
import 'package:finance_ai_app/screens/auth/password_recovery_errors.dart';

import '../../helpers/captured_logs.dart';

const String _email = 'an@example.com';
const String _otp = '123456';
const String _password = 'NewPass456';

/// What core-backend's catch-all puts in a 500: the raw exception.
const String _rawServerFailure =
    'Hệ thống sự cố: could not execute statement; SQL [n/a]; constraint [users_pkey]';

final RequestOptions _sendOtpRequest = RequestOptions(
  method: 'POST',
  path: '/api/auth/forgot-password',
  queryParameters: {'email': _email},
);

final RequestOptions _resetRequest = RequestOptions(
  method: 'POST',
  path: '/api/auth/reset-password',
  data: {'email': _email, 'otp': _otp, 'newPassword': _password},
);

/// The server answered [status] with [body].
DioException _answer(int status, Object? body, [RequestOptions? request]) {
  final options = request ?? _resetRequest;
  return DioException.badResponse(
    statusCode: status,
    requestOptions: options,
    response: Response<Object?>(
      requestOptions: options,
      statusCode: status,
      data: body,
    ),
  );
}

void main() {
  final vi = lookupAppLocalizations(const Locale('vi'));
  final en = lookupAppLocalizations(const Locale('en'));

  String messageOf(
    Object error, {
    PasswordRecoveryRequest request = PasswordRecoveryRequest.resetPassword,
    AppLocalizations? loc,
  }) => PasswordRecoveryErrors.messageOf(request, error, loc ?? vi);

  group('messageOf', () {
    test('a refusal (4xx) shows the message core-backend wrote for it', () {
      const wrongOtp = 'Mã OTP không chính xác. Bạn còn 2 lần thử.';
      expect(
        messageOf(_answer(400, {'code': 400, 'message': wrongOtp})),
        wrongOtp,
      );
      expect(
        messageOf(
          _answer(400, {'message': '  Email không tồn tại trong hệ thống  '}),
          request: PasswordRecoveryRequest.sendOtp,
        ),
        'Email không tồn tại trong hệ thống',
      );
      expect(
        messageOf(
          _answer(409, {'message': 'Mật khẩu vừa được đổi ở nơi khác.'}),
        ),
        'Mật khẩu vừa được đổi ở nơi khác.',
      );
    });

    test(
      'a 4xx without a readable message gets the fixed text of its request',
      () {
        for (final body in <Object?>[
          null,
          '<html>404 Not Found</html>',
          {'code': 400},
          {'message': 42},
          {'message': '   '},
        ]) {
          for (final status in [400, 401, 404]) {
            expect(
              messageOf(_answer(status, body)),
              vi.resetPasswordRejected,
              reason: '$status $body',
            );
            expect(
              messageOf(
                _answer(status, body, _sendOtpRequest),
                request: PasswordRecoveryRequest.sendOtp,
              ),
              vi.forgotPasswordRejected,
              reason: '$status $body',
            );
          }
        }
      },
    );

    test(
      'a 5xx shows the fixed server text, never the raw exception core-backend put in it',
      () {
        for (final status in [500, 502, 503]) {
          final message = messageOf(
            _answer(status, {'code': status, 'message': _rawServerFailure}),
          );
          expect(message, vi.passwordRecoveryServerError, reason: '$status');
          expect(message, isNot(contains('SQL')));
        }
      },
    );

    test(
      'no answer at all: a timeout asks to try again, the rest says the server cannot be reached',
      () {
        for (final timeout in [
          DioException.connectionTimeout(
            timeout: const Duration(seconds: 15),
            requestOptions: _resetRequest,
          ),
          DioException.sendTimeout(
            timeout: const Duration(seconds: 15),
            requestOptions: _resetRequest,
          ),
          DioException.receiveTimeout(
            timeout: const Duration(seconds: 30),
            requestOptions: _resetRequest,
          ),
        ]) {
          expect(
            messageOf(timeout),
            vi.passwordRecoveryTimeout,
            reason: timeout.type.name,
          );
        }
        for (final unreachable in [
          DioException.connectionError(
            requestOptions: _sendOtpRequest,
            reason: 'Failed host lookup: api.ledgermind.dev',
          ),
          DioException.badCertificate(requestOptions: _sendOtpRequest),
          DioException(
            requestOptions: _sendOtpRequest,
            error: const SocketException('Connection reset by peer'),
          ),
        ]) {
          expect(
            messageOf(unreachable, request: PasswordRecoveryRequest.sendOtp),
            vi.passwordRecoveryNetworkError,
            reason: unreachable.type.name,
          );
        }
      },
    );

    test(
      'an error that does not come from Dio gets the generic text, never its own',
      () {
        final message = messageOf(StateError('$_email $_otp $_password'));

        expect(message, vi.passwordRecoveryUnexpectedError);
        for (final secret in [_email, _otp, _password]) {
          expect(message, isNot(contains(secret)));
        }
      },
    );

    test('the fixed texts follow the language of the app', () {
      expect(
        messageOf(_answer(500, null), loc: en),
        en.passwordRecoveryServerError,
      );
      expect(
        messageOf(
          _answer(404, null),
          loc: en,
          request: PasswordRecoveryRequest.sendOtp,
        ),
        en.forgotPasswordRejected,
      );
      expect(
        en.passwordRecoveryServerError,
        isNot(vi.passwordRecoveryServerError),
      );
    });
  });

  test(
    'log keeps the request and the status or kind of error, never the email, the OTP or the password',
    () async {
      await withCapturedLogs((logs) async {
        PasswordRecoveryErrors.log(
          PasswordRecoveryRequest.sendOtp,
          _answer(400, {
            'message': 'Email $_email không tồn tại',
          }, _sendOtpRequest),
        );
        PasswordRecoveryErrors.log(
          PasswordRecoveryRequest.sendOtp,
          DioException.connectionError(
            requestOptions: _sendOtpRequest,
            reason: _email,
          ),
        );
        PasswordRecoveryErrors.log(
          PasswordRecoveryRequest.resetPassword,
          StateError('$_otp $_password'),
        );

        expect(logs, [
          'PasswordRecovery: sendOtp failed (400).',
          'PasswordRecovery: sendOtp failed (connectionError).',
          'PasswordRecovery: resetPassword failed (StateError).',
        ]);
      });
    },
  );
}
