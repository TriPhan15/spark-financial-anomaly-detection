import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'package:finance_ai_app/l10n/app_localizations.dart';

/// The two requests of the password recovery.
enum PasswordRecoveryRequest {
  /// POST /api/auth/forgot-password: emails a new OTP, also when the user asks to resend it.
  sendOtp,

  /// POST /api/auth/reset-password: sets the new password with the OTP.
  resetPassword,
}

/// What the user is told, and what the log keeps, when a password recovery request fails.
class PasswordRecoveryErrors {
  static const Set<DioExceptionType> _timeouts = {
    DioExceptionType.connectionTimeout,
    DioExceptionType.sendTimeout,
    DioExceptionType.receiveTimeout,
  };

  /// The server's own message when it refused the request (4xx): that is where core-backend words its answer (unknown
  /// email, wrong or expired OTP, weak password), and a 4xx without one gets the fixed text of [request]. Anything else
  /// gets a fixed text too, so the exception, its stack trace and the request URL (the email travels in the query
  /// string of forgot-password) never reach the screen. The message of a 5xx is not shown either: core-backend fills it
  /// with the raw exception.
  static String messageOf(
    PasswordRecoveryRequest request,
    Object error,
    AppLocalizations loc,
  ) {
    if (error is! DioException) return loc.passwordRecoveryUnexpectedError;
    final status = error.response?.statusCode;
    if (status == null) {
      return _timeouts.contains(error.type)
          ? loc.passwordRecoveryTimeout
          : loc.passwordRecoveryNetworkError;
    }
    if (status >= HttpStatus.internalServerError) {
      return loc.passwordRecoveryServerError;
    }
    return _serverMessageOf(error.response?.data) ??
        _rejectedMessageOf(request, loc);
  }

  /// Logs that [request] failed, with the status or the kind of error only: never the email, the OTP or the password,
  /// which the text of the exception or its request URL could carry.
  static void log(PasswordRecoveryRequest request, Object error) {
    final reason = error is DioException
        ? '${error.response?.statusCode ?? error.type.name}'
        : '${error.runtimeType}';
    debugPrint('PasswordRecovery: ${request.name} failed ($reason).');
  }

  static String? _serverMessageOf(Object? body) {
    final message = body is Map ? body['message'] : null;
    if (message is! String) return null;
    final trimmed = message.trim();
    return trimmed.isEmpty ? null : trimmed;
  }

  static String _rejectedMessageOf(
    PasswordRecoveryRequest request,
    AppLocalizations loc,
  ) => switch (request) {
    PasswordRecoveryRequest.sendOtp => loc.forgotPasswordRejected,
    PasswordRecoveryRequest.resetPassword => loc.resetPasswordRejected,
  };
}
