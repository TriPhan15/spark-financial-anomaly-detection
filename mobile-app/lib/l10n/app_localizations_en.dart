// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get settings => 'Settings';

  @override
  String get language => 'Language';

  @override
  String get theme => 'Theme';

  @override
  String get dark => 'Dark';

  @override
  String get light => 'Light';

  @override
  String get system => 'System';

  @override
  String get systemThemeDescription => 'Follows your device setting';

  @override
  String get english => 'English';

  @override
  String get vietnamese => 'Tiếng Việt';

  @override
  String get settingsSaveFailed =>
      'Couldn\'t save this setting. The change applies until you close the app.';

  @override
  String get notificationsTitle => 'Notifications';

  @override
  String get notificationsMarkAllRead => 'Mark all as read';

  @override
  String get notificationsEmpty => 'You have no notifications yet.';

  @override
  String get notificationsLoadFailed => 'Couldn\'t load your notifications.';

  @override
  String get notificationsLoadMoreFailed =>
      'Couldn\'t load more notifications.';

  @override
  String get notificationsRetry => 'Try again';

  @override
  String get notificationsMarkReadFailed =>
      'Couldn\'t mark the notification as read. Please try again.';

  @override
  String get notificationsMarkAllReadFailed =>
      'Couldn\'t mark your notifications as read. Please try again.';

  @override
  String get notificationsDetailUnavailable =>
      'This notification can\'t be opened yet.';

  @override
  String get notificationsUnread => 'Unread';

  @override
  String get notificationTypeBudgetWarning => 'Budget almost used up';

  @override
  String get notificationTypeBudgetExceeded => 'Budget exceeded';

  @override
  String get notificationTypeTransactionAlert => 'Transaction alert';

  @override
  String get notificationTypeReceiptProcessed => 'Receipt processed';

  @override
  String get notificationTypeOther => 'Notification';

  @override
  String get sessionEnded =>
      'Your session has ended, for example because your password was changed. Please sign in again.';

  @override
  String get dialogGotIt => 'Got it';

  @override
  String get forgotPasswordTitle => 'Forgot password';

  @override
  String get forgotPasswordHeading => 'Get a verification code';

  @override
  String get forgotPasswordDescription =>
      'Enter the email of your account. We\'ll send you a one-time code (OTP) to set a new password.';

  @override
  String get forgotPasswordEmailLabel => 'Email';

  @override
  String get forgotPasswordEmailHint => 'Enter your email address';

  @override
  String get forgotPasswordEmailRequired => 'Please enter your email.';

  @override
  String get forgotPasswordEmailInvalid =>
      'Please enter a valid email address.';

  @override
  String get forgotPasswordSendOtp => 'Send code';

  @override
  String get forgotPasswordSendFailed => 'Couldn\'t send the code';

  @override
  String get forgotPasswordRejected =>
      'The code couldn\'t be sent. Please check the email and try again.';

  @override
  String get resetPasswordTitle => 'Reset password';

  @override
  String get resetPasswordHeading => 'Create a new password';

  @override
  String resetPasswordDescription(String email) {
    return 'Enter the code we sent to $email, then choose a new password.';
  }

  @override
  String get resetPasswordOtpLabel => 'Verification code (OTP)';

  @override
  String resetPasswordOtpHint(int length) {
    return '$length-digit code';
  }

  @override
  String get resetPasswordOtpRequired => 'Please enter the code.';

  @override
  String resetPasswordOtpInvalid(int length) {
    return 'The code must be exactly $length digits.';
  }

  @override
  String get resetPasswordResendOtp => 'Resend code';

  @override
  String resetPasswordResendIn(int seconds) {
    return 'Resend code in ${seconds}s';
  }

  @override
  String get resetPasswordOtpResent =>
      'A new code has been sent. Please check your email.';

  @override
  String get resetPasswordNewPasswordLabel => 'New password';

  @override
  String get resetPasswordNewPasswordHint => 'Enter a new password';

  @override
  String get resetPasswordPasswordRequired => 'Please enter a new password.';

  @override
  String resetPasswordPasswordPolicy(int min, int max) {
    return '$min to $max characters, with at least one letter and one digit.';
  }

  @override
  String get resetPasswordConfirmPasswordLabel => 'Confirm new password';

  @override
  String get resetPasswordConfirmPasswordHint => 'Enter the new password again';

  @override
  String get resetPasswordConfirmRequired => 'Please confirm the new password.';

  @override
  String get resetPasswordMismatch => 'The passwords don\'t match.';

  @override
  String get resetPasswordSubmit => 'Reset password';

  @override
  String get resetPasswordSubmitting => 'Processing...';

  @override
  String get resetPasswordSuccessTitle => 'Password reset';

  @override
  String get resetPasswordSuccessMessage =>
      'Your password has been changed. Please sign in with your new password.';

  @override
  String get resetPasswordBackToLogin => 'Back to sign in';

  @override
  String get resetPasswordFailed => 'Couldn\'t reset your password';

  @override
  String get resetPasswordRejected =>
      'Your password couldn\'t be reset. Please check the code and try again.';

  @override
  String get passwordShow => 'Show password';

  @override
  String get passwordHide => 'Hide password';

  @override
  String get passwordRecoverySending => 'Sending...';

  @override
  String get passwordRecoveryServerOnly =>
      'Password recovery needs a connection to the server and isn\'t available in demo mode.';

  @override
  String get passwordRecoveryNetworkError =>
      'Can\'t reach the server. Please check your connection and try again.';

  @override
  String get passwordRecoveryTimeout =>
      'The server is taking too long to respond. Please try again.';

  @override
  String get passwordRecoveryServerError =>
      'The server ran into a problem. Please try again later.';

  @override
  String get passwordRecoveryUnexpectedError =>
      'Something went wrong. Please try again.';
}
