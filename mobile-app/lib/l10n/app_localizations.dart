import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_vi.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations? of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations);
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('vi'),
    Locale('en'),
  ];

  /// Title of the settings screen.
  ///
  /// In en, this message translates to:
  /// **'Settings'**
  String get settings;

  /// Label of the language setting.
  ///
  /// In en, this message translates to:
  /// **'Language'**
  String get language;

  /// Label of the theme setting.
  ///
  /// In en, this message translates to:
  /// **'Theme'**
  String get theme;

  /// Dark theme option.
  ///
  /// In en, this message translates to:
  /// **'Dark'**
  String get dark;

  /// Light theme option.
  ///
  /// In en, this message translates to:
  /// **'Light'**
  String get light;

  /// Theme option that follows the device setting.
  ///
  /// In en, this message translates to:
  /// **'System'**
  String get system;

  /// Subtitle of the System theme option.
  ///
  /// In en, this message translates to:
  /// **'Follows your device setting'**
  String get systemThemeDescription;

  /// Name of the English language, written in English in every locale so that its speakers can find it.
  ///
  /// In en, this message translates to:
  /// **'English'**
  String get english;

  /// Name of the Vietnamese language, written in Vietnamese in every locale so that its speakers can find it.
  ///
  /// In en, this message translates to:
  /// **'Tiếng Việt'**
  String get vietnamese;

  /// Snackbar shown when a theme or language choice could not be written to the device.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t save this setting. The change applies until you close the app.'**
  String get settingsSaveFailed;

  /// Title of the notification screen, also the tooltip of the bell on the home screen.
  ///
  /// In en, this message translates to:
  /// **'Notifications'**
  String get notificationsTitle;

  /// App bar action of the notification screen that marks every notification as read.
  ///
  /// In en, this message translates to:
  /// **'Mark all as read'**
  String get notificationsMarkAllRead;

  /// Shown on the notification screen when the user has no notification at all.
  ///
  /// In en, this message translates to:
  /// **'You have no notifications yet.'**
  String get notificationsEmpty;

  /// Shown when the list of notifications could not be loaded from the server.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t load your notifications.'**
  String get notificationsLoadFailed;

  /// Shown at the end of the list when the next page of notifications could not be loaded.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t load more notifications.'**
  String get notificationsLoadMoreFailed;

  /// Button that asks the server for the notifications again after a failure.
  ///
  /// In en, this message translates to:
  /// **'Try again'**
  String get notificationsRetry;

  /// Snackbar shown when the server refused or missed marking one notification as read; it shows as unread again.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t mark the notification as read. Please try again.'**
  String get notificationsMarkReadFailed;

  /// Snackbar shown when marking every notification as read failed; they show as unread again.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t mark your notifications as read. Please try again.'**
  String get notificationsMarkAllReadFailed;

  /// Snackbar shown when the tapped notification has no screen to open in the app yet.
  ///
  /// In en, this message translates to:
  /// **'This notification can\'t be opened yet.'**
  String get notificationsDetailUnavailable;

  /// Screen reader label of the dot that marks an unread notification.
  ///
  /// In en, this message translates to:
  /// **'Unread'**
  String get notificationsUnread;

  /// Heading of a notification saying that spending reached 80% of a budget.
  ///
  /// In en, this message translates to:
  /// **'Budget almost used up'**
  String get notificationTypeBudgetWarning;

  /// Heading of a notification saying that spending reached or passed 100% of a budget.
  ///
  /// In en, this message translates to:
  /// **'Budget exceeded'**
  String get notificationTypeBudgetExceeded;

  /// Heading of a notification about one transaction.
  ///
  /// In en, this message translates to:
  /// **'Transaction alert'**
  String get notificationTypeTransactionAlert;

  /// Heading of a notification saying that a receipt has been processed.
  ///
  /// In en, this message translates to:
  /// **'Receipt processed'**
  String get notificationTypeReceiptProcessed;

  /// Heading of a notification whose kind this version of the app does not know.
  ///
  /// In en, this message translates to:
  /// **'Notification'**
  String get notificationTypeOther;

  /// SnackBar shown on the login screen after the app signed the user out because the session could not be renewed, e.g. the password was changed or reset on another device.
  ///
  /// In en, this message translates to:
  /// **'Your session has ended, for example because your password was changed. Please sign in again.'**
  String get sessionEnded;

  /// Button that closes an error dialog.
  ///
  /// In en, this message translates to:
  /// **'Got it'**
  String get dialogGotIt;

  /// App bar title of the forgot-password screen, the first step of the password recovery.
  ///
  /// In en, this message translates to:
  /// **'Forgot password'**
  String get forgotPasswordTitle;

  /// Heading of the forgot-password screen, above its description.
  ///
  /// In en, this message translates to:
  /// **'Get a verification code'**
  String get forgotPasswordHeading;

  /// Explains on the forgot-password screen that a one-time code will be emailed to the address typed below.
  ///
  /// In en, this message translates to:
  /// **'Enter the email of your account. We\'ll send you a one-time code (OTP) to set a new password.'**
  String get forgotPasswordDescription;

  /// Label above the email field of the forgot-password screen.
  ///
  /// In en, this message translates to:
  /// **'Email'**
  String get forgotPasswordEmailLabel;

  /// Hint inside the empty email field of the forgot-password screen.
  ///
  /// In en, this message translates to:
  /// **'Enter your email address'**
  String get forgotPasswordEmailHint;

  /// Error under the email field when the user asks for a code without typing an email.
  ///
  /// In en, this message translates to:
  /// **'Please enter your email.'**
  String get forgotPasswordEmailRequired;

  /// Error under the email field when what was typed is not an email address.
  ///
  /// In en, this message translates to:
  /// **'Please enter a valid email address.'**
  String get forgotPasswordEmailInvalid;

  /// Button of the forgot-password screen that asks the server to email a one-time code.
  ///
  /// In en, this message translates to:
  /// **'Send code'**
  String get forgotPasswordSendOtp;

  /// Title of the error dialog shown when the one-time code could not be sent or sent again.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t send the code'**
  String get forgotPasswordSendFailed;

  /// Error dialog text when the server refused to send a one-time code without saying why.
  ///
  /// In en, this message translates to:
  /// **'The code couldn\'t be sent. Please check the email and try again.'**
  String get forgotPasswordRejected;

  /// App bar title of the reset-password screen, where the user types the one-time code and a new password.
  ///
  /// In en, this message translates to:
  /// **'Reset password'**
  String get resetPasswordTitle;

  /// Heading of the reset-password screen, above its description.
  ///
  /// In en, this message translates to:
  /// **'Create a new password'**
  String get resetPasswordHeading;

  /// Description of the reset-password screen. The email is shown partly hidden.
  ///
  /// In en, this message translates to:
  /// **'Enter the code we sent to {email}, then choose a new password.'**
  String resetPasswordDescription(String email);

  /// Label above the one-time code field of the reset-password screen.
  ///
  /// In en, this message translates to:
  /// **'Verification code (OTP)'**
  String get resetPasswordOtpLabel;

  /// Hint inside the empty one-time code field.
  ///
  /// In en, this message translates to:
  /// **'{length}-digit code'**
  String resetPasswordOtpHint(int length);

  /// Error under the one-time code field when it is empty.
  ///
  /// In en, this message translates to:
  /// **'Please enter the code.'**
  String get resetPasswordOtpRequired;

  /// Error under the one-time code field when it does not hold exactly the expected number of digits.
  ///
  /// In en, this message translates to:
  /// **'The code must be exactly {length} digits.'**
  String resetPasswordOtpInvalid(int length);

  /// Button that emails a new one-time code to the same address.
  ///
  /// In en, this message translates to:
  /// **'Resend code'**
  String get resetPasswordResendOtp;

  /// Disabled resend button while the user has to wait before asking for another code.
  ///
  /// In en, this message translates to:
  /// **'Resend code in {seconds}s'**
  String resetPasswordResendIn(int seconds);

  /// SnackBar shown when a new one-time code has been sent.
  ///
  /// In en, this message translates to:
  /// **'A new code has been sent. Please check your email.'**
  String get resetPasswordOtpResent;

  /// Label above the new password field of the reset-password screen.
  ///
  /// In en, this message translates to:
  /// **'New password'**
  String get resetPasswordNewPasswordLabel;

  /// Hint inside the empty new password field.
  ///
  /// In en, this message translates to:
  /// **'Enter a new password'**
  String get resetPasswordNewPasswordHint;

  /// Error under the new password field when it is empty.
  ///
  /// In en, this message translates to:
  /// **'Please enter a new password.'**
  String get resetPasswordPasswordRequired;

  /// The password rules, shown under the new password field, in the error colour when the password breaks them.
  ///
  /// In en, this message translates to:
  /// **'{min} to {max} characters, with at least one letter and one digit.'**
  String resetPasswordPasswordPolicy(int min, int max);

  /// Label above the field where the new password is typed a second time.
  ///
  /// In en, this message translates to:
  /// **'Confirm new password'**
  String get resetPasswordConfirmPasswordLabel;

  /// Hint inside the empty confirmation field.
  ///
  /// In en, this message translates to:
  /// **'Enter the new password again'**
  String get resetPasswordConfirmPasswordHint;

  /// Error under the confirmation field when it is empty.
  ///
  /// In en, this message translates to:
  /// **'Please confirm the new password.'**
  String get resetPasswordConfirmRequired;

  /// Error under the confirmation field when it differs from the new password.
  ///
  /// In en, this message translates to:
  /// **'The passwords don\'t match.'**
  String get resetPasswordMismatch;

  /// Button that sets the new password with the one-time code.
  ///
  /// In en, this message translates to:
  /// **'Reset password'**
  String get resetPasswordSubmit;

  /// Label of the disabled reset button while the request is on its way.
  ///
  /// In en, this message translates to:
  /// **'Processing...'**
  String get resetPasswordSubmitting;

  /// Title of the dialog shown when the password has been reset.
  ///
  /// In en, this message translates to:
  /// **'Password reset'**
  String get resetPasswordSuccessTitle;

  /// Text of the dialog shown when the password has been reset; the user is not signed in automatically.
  ///
  /// In en, this message translates to:
  /// **'Your password has been changed. Please sign in with your new password.'**
  String get resetPasswordSuccessMessage;

  /// Button of the success dialog that opens the login screen.
  ///
  /// In en, this message translates to:
  /// **'Back to sign in'**
  String get resetPasswordBackToLogin;

  /// Title of the error dialog shown when the password could not be reset.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t reset your password'**
  String get resetPasswordFailed;

  /// Error dialog text when the server refused the reset without saying why.
  ///
  /// In en, this message translates to:
  /// **'Your password couldn\'t be reset. Please check the code and try again.'**
  String get resetPasswordRejected;

  /// Tooltip of the eye button that shows a hidden password.
  ///
  /// In en, this message translates to:
  /// **'Show password'**
  String get passwordShow;

  /// Tooltip of the eye button that hides a shown password.
  ///
  /// In en, this message translates to:
  /// **'Hide password'**
  String get passwordHide;

  /// Label of a disabled send or resend button while the one-time code request is on its way.
  ///
  /// In en, this message translates to:
  /// **'Sending...'**
  String get passwordRecoverySending;

  /// Shown when the user tries to recover a password in the offline demo mode, where there is no server to send a code.
  ///
  /// In en, this message translates to:
  /// **'Password recovery needs a connection to the server and isn\'t available in demo mode.'**
  String get passwordRecoveryServerOnly;

  /// Error dialog text when a password recovery request got no answer from the server.
  ///
  /// In en, this message translates to:
  /// **'Can\'t reach the server. Please check your connection and try again.'**
  String get passwordRecoveryNetworkError;

  /// Error dialog text when a password recovery request timed out.
  ///
  /// In en, this message translates to:
  /// **'The server is taking too long to respond. Please try again.'**
  String get passwordRecoveryTimeout;

  /// Error dialog text when the server failed (5xx) on a password recovery request.
  ///
  /// In en, this message translates to:
  /// **'The server ran into a problem. Please try again later.'**
  String get passwordRecoveryServerError;

  /// Error dialog text when a password recovery request failed for a reason the app does not know.
  ///
  /// In en, this message translates to:
  /// **'Something went wrong. Please try again.'**
  String get passwordRecoveryUnexpectedError;
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'vi'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'vi':
      return AppLocalizationsVi();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
