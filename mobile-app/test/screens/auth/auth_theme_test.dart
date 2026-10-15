import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/screens/auth/forgot_password_page.dart';
import 'package:finance_ai_app/screens/auth/login_page.dart';
import 'package:finance_ai_app/screens/auth/register_page.dart';
import 'package:finance_ai_app/screens/auth/reset_password_page.dart';

import '../../helpers/app_settings_scope.dart';
import '../../helpers/fake_settings_repository.dart';
import '../../helpers/theme_colors.dart';

/// Pumps [screen] as the app shows it, in [mode], and returns the theme it was built with.
Future<ThemeData> _show(
  WidgetTester tester,
  Widget screen,
  ThemeMode mode,
) async {
  // Tall and wide, because the test font is much wider than a real one.
  tester.view.physicalSize = const Size(800, 2000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    withAppSettings(screen, settings: FakeSettingsRepository(themeMode: mode)),
  );
  await tester.pumpAndSettle();
  return Theme.of(tester.element(find.byWidget(screen)));
}

/// The decoration a text field is drawn with, once the theme's inputDecorationTheme is applied.
InputDecoration _fieldDecoration(WidgetTester tester, Finder field) => tester
    .widget<InputDecorator>(
      find.descendant(of: field, matching: find.byType(InputDecorator)),
    )
    .decoration;

/// The round social sign-in buttons: a surface circle, with the soft shadow only where it shows.
void _expectSocialButtons(WidgetTester tester, ColorScheme colors) {
  for (final icon in [Icons.g_mobiledata_rounded, Icons.facebook]) {
    final circle = boxDecoration(tester, find.byIcon(icon));
    expect(circle.color, colors.surface);
    expect(circle.boxShadow, AppTheme.softShadowFor(colors));
    expect(
      contrastRatio(paintedColor(tester, find.byIcon(icon))!, colors.surface),
      greaterThanOrEqualTo(3),
      reason: 'the brand colour of $icon on its button',
    );
  }
}

void main() {
  // The language cubit of withAppSettings sets the default locale of intl.
  tearDown(() => Intl.defaultLocale = null);

  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    group('${mode.name} theme', () {
      testWidgets(
        'LoginScreen: page, logo, texts, fields, divider, buttons and the sign-up link follow the theme',
        (tester) async {
          final theme = await _show(tester, const LoginScreen(), mode);
          final colors = theme.colorScheme;

          expect(
            materialColor(tester, find.byType(Scaffold)),
            theme.scaffoldBackgroundColor,
          );
          expect(boxColor(tester, find.byType(Image)), colors.primaryContainer);
          expect(
            paintedColor(tester, find.text('Ledgermind App')),
            colors.primary,
          );
          expect(
            paintedColor(tester, find.text('Quản lý tài chính thông minh')),
            colors.onSurfaceVariant,
          );
          expect(paintedColor(tester, find.text('Email')), colors.onSurface);
          expect(
            paintedColor(tester, find.text('Nhập địa chỉ email')),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.visibility_off_outlined)),
            colors.onSurfaceVariant,
          );
          await tester.enterText(
            find.byType(TextField).first,
            'an@example.com',
          );
          await tester.pump();
          expect(
            tester
                .widget<EditableText>(find.byType(EditableText).first)
                .style
                .color,
            colors.onSurface,
          );
          expect(
            _fieldDecoration(tester, find.byType(TextField).first).fillColor,
            colors.surface,
          );

          expect(
            paintedColor(tester, find.text('Ghi nhớ đăng nhập')),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.text('Quên mật khẩu?')),
            colors.primary,
          );
          expect(
            materialColor(tester, find.byType(ElevatedButton)),
            colors.primary,
          );
          expect(
            paintedColor(tester, find.text('Đăng nhập')),
            colors.onPrimary,
          );

          final signUp =
              tester
                      .widget<RichText>(
                        find.byWidgetPredicate(
                          (widget) =>
                              widget is RichText &&
                              widget.text.toPlainText() ==
                                  'Chưa có tài khoản? Đăng ký ngay',
                        ),
                      )
                      .text
                  as TextSpan;
          expect(signUp.style?.color, colors.onSurfaceVariant);
          expect(
            (signUp.children!.single as TextSpan).style?.color,
            colors.primary,
          );

          expect(
            tester.widget<Divider>(find.byType(Divider).first).color,
            colors.onSurfaceVariant.withValues(alpha: 0.3),
          );
          expect(
            paintedColor(tester, find.text('Hoặc đăng nhập với')),
            colors.onSurfaceVariant,
          );
          _expectSocialButtons(tester, colors);
        },
      );

      testWidgets(
        'ForgotPasswordScreen: page, app bar, texts, field, its error and the button follow the theme',
        (tester) async {
          final theme = await _show(tester, const ForgotPasswordScreen(), mode);
          final colors = theme.colorScheme;

          expect(
            materialColor(tester, find.byType(Scaffold)),
            theme.scaffoldBackgroundColor,
          );
          expect(
            paintedColor(tester, find.text('Quên mật khẩu')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('Nhận mã OTP')),
            colors.onSurface,
          );
          expect(
            paintedColor(
              tester,
              find.text(
                'Nhập email tài khoản của bạn. Chúng tôi sẽ gửi mã OTP để bạn đặt mật khẩu mới.',
              ),
            ),
            colors.onSurfaceVariant,
          );
          expect(paintedColor(tester, find.text('Email')), colors.onSurface);
          expect(
            paintedColor(tester, find.text('Nhập địa chỉ email')),
            colors.onSurfaceVariant,
          );
          expect(
            _fieldDecoration(tester, find.byType(TextFormField)).fillColor,
            colors.surface,
          );
          expect(
            materialColor(tester, find.byType(ElevatedButton)),
            colors.primary,
          );
          expect(
            paintedColor(tester, find.text('Gửi mã OTP')),
            colors.onPrimary,
          );

          // Sending with no email shows the error under the field in the error colour.
          await tester.tap(find.byType(ElevatedButton));
          await tester.pumpAndSettle();
          expect(
            paintedColor(tester, find.text('Vui lòng nhập email.')),
            colors.error,
          );
        },
      );

      testWidgets(
        'ResetPasswordScreen: texts, fields, rules, eye buttons, resend and reset buttons follow the theme',
        (tester) async {
          final theme = await _show(
            tester,
            const ResetPasswordScreen(email: 'an@example.com'),
            mode,
          );
          final colors = theme.colorScheme;

          expect(
            materialColor(tester, find.byType(Scaffold)),
            theme.scaffoldBackgroundColor,
          );
          expect(
            paintedColor(tester, find.text('Tạo mật khẩu mới')),
            colors.onSurface,
          );
          expect(
            paintedColor(
              tester,
              find.text(
                'Nhập mã OTP đã được gửi tới a***@example.com, sau đó đặt mật khẩu mới.',
              ),
            ),
            colors.onSurfaceVariant,
          );
          expect(paintedColor(tester, find.text('Mã OTP')), colors.onSurface);
          expect(
            paintedColor(tester, find.text('Mã gồm 6 chữ số')),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(
              tester,
              find.text(
                'Từ 8 đến 72 ký tự, gồm ít nhất một chữ cái và một chữ số.',
              ),
            ),
            colors.onSurfaceVariant,
            reason: 'the password rules under the field',
          );
          for (final field in tester.widgetList<InputDecorator>(
            find.byType(InputDecorator),
          )) {
            expect(field.decoration.fillColor, colors.surface);
          }
          for (final eye in tester.widgetList<Icon>(
            find.byIcon(Icons.visibility_off_outlined),
          )) {
            expect(eye.color, colors.onSurfaceVariant);
          }
          expect(
            materialColor(tester, find.byType(ElevatedButton)),
            colors.primary,
          );
          expect(
            paintedColor(
              tester,
              find.descendant(
                of: find.byType(ElevatedButton),
                matching: find.text('Đặt lại mật khẩu'),
              ),
            ),
            colors.onPrimary,
          );
          // Once the countdown is over, "resend" takes the primary colour of the theme; the Material of a button fades
          // its label colour, so the fade has to end before the colour is read.
          await tester.pump(ResetPasswordScreen.resendCooldown);
          await tester.pumpAndSettle();
          expect(
            paintedColor(tester, find.text('Gửi lại mã OTP')),
            colors.primary,
          );
        },
      );

      testWidgets(
        'RegisterScreen: page, app bar, texts, fields, terms, checklist card and social buttons follow the theme',
        (tester) async {
          final theme = await _show(tester, const RegisterScreen(), mode);
          final colors = theme.colorScheme;
          final semantic = theme.extension<SemanticColors>()!;

          expect(
            materialColor(tester, find.byType(Scaffold)),
            theme.scaffoldBackgroundColor,
          );
          expect(paintedColor(tester, find.text('Đăng ký')), colors.onSurface);
          expect(
            paintedColor(tester, find.text('Tạo tài khoản').first),
            colors.onSurface,
            reason: 'the heading',
          );
          expect(
            paintedColor(
              tester,
              find.text('Điền thông tin để bắt đầu quản lý tài chính.'),
            ),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.text('Họ và tên')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('Nhập họ và tên')),
            colors.onSurfaceVariant,
          );
          final terms =
              tester
                      .widget<Text>(
                        find.text(
                          'Bằng việc đăng ký, bạn đồng ý với Điều khoản và Điều kiện sử dụng',
                        ),
                      )
                      .textSpan!
                  as TextSpan;
          expect(terms.style?.color, colors.onSurfaceVariant);
          for (final link in terms.children!.whereType<TextSpan>().where(
            (span) => span.text != ' và ',
          )) {
            expect(link.style?.color, colors.primary, reason: link.text);
          }
          expect(
            materialColor(tester, find.byType(ElevatedButton)),
            colors.primary,
          );
          expect(
            tester.widget<Divider>(find.byType(Divider).first).color,
            colors.onSurfaceVariant.withValues(alpha: 0.3),
          );
          _expectSocialButtons(tester, colors);

          // Typing a password opens the checklist: upper and lower case are met, the other rules are not.
          await tester.enterText(find.byType(TextField).at(3), 'Abc');
          await tester.pumpAndSettle();

          expect(
            boxDecoration(tester, find.text('Mật khẩu cần:')),
            AppTheme.cardDecoration(colors),
          );
          expect(
            paintedColor(tester, find.text('Mật khẩu cần:')),
            colors.onSurface,
          );
          expect(find.byIcon(Icons.check_circle), findsNWidgets(2));
          for (final icon in tester.widgetList<Icon>(
            find.byIcon(Icons.check_circle),
          )) {
            expect(icon.color, semantic.successColor);
          }
          expect(find.byIcon(Icons.radio_button_unchecked), findsNWidgets(3));
          for (final icon in tester.widgetList<Icon>(
            find.byIcon(Icons.radio_button_unchecked),
          )) {
            expect(icon.color, colors.onSurfaceVariant);
          }
          expect(
            paintedColor(tester, find.text('Chữ viết hoa (A-Z)')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('Chữ số (0-9)')),
            colors.onSurfaceVariant,
          );
          // Light mode keeps its old green (2.3:1 on white, unchanged on purpose); the dark palette is readable.
          if (mode == ThemeMode.dark) {
            expect(
              contrastRatio(semantic.successColor, colors.surface),
              greaterThanOrEqualTo(3),
              reason: 'a met rule is visible on the dark card',
            );
          }
        },
      );
    });
  }

  testWidgets('light mode keeps the colours the Login screen had before', (
    tester,
  ) async {
    await _show(tester, const LoginScreen(), ThemeMode.light);

    expect(materialColor(tester, find.byType(Scaffold)), AppTheme.bgLight);
    expect(boxColor(tester, find.byType(Image)), AppTheme.mintLight);
    expect(paintedColor(tester, find.text('Ledgermind App')), AppTheme.primary);
    expect(
      paintedColor(tester, find.text('Quản lý tài chính thông minh')),
      AppTheme.textSecondary,
    );
    expect(paintedColor(tester, find.text('Email')), AppTheme.textPrimary);
    expect(
      paintedColor(tester, find.text('Nhập địa chỉ email')),
      AppTheme.textGray,
    );
    expect(
      tester.widget<Divider>(find.byType(Divider).first).color,
      AppTheme.textGray.withValues(alpha: 0.3),
    );
    final google = boxDecoration(
      tester,
      find.byIcon(Icons.g_mobiledata_rounded),
    );
    expect(google.color, Colors.white);
    expect(google.boxShadow, AppTheme.softShadow);
    expect(
      materialColor(tester, find.byType(ElevatedButton)),
      AppTheme.mintAccent,
    );
    expect(paintedColor(tester, find.text('Đăng nhập')), AppTheme.navyDark);
  });

  testWidgets(
    'light mode keeps the Register checklist white with the positive ticks, now without a shadow',
    (tester) async {
      await _show(tester, const RegisterScreen(), ThemeMode.light);

      await tester.enterText(find.byType(TextField).at(3), 'Abc');
      await tester.pumpAndSettle();

      final card = boxDecoration(tester, find.text('Mật khẩu cần:'));
      expect(card.color, AppTheme.card.color);
      expect(card.borderRadius, AppTheme.card.borderRadius);
      expect(card.boxShadow, isNull);
      expect(
        tester.widget<Icon>(find.byIcon(Icons.check_circle).first).color,
        AppTheme.positive,
      );
      expect(
        tester
            .widget<Icon>(find.byIcon(Icons.radio_button_unchecked).first)
            .color,
        AppTheme.textGray,
      );
      expect(
        paintedColor(tester, find.text('Chữ viết hoa (A-Z)')),
        AppTheme.textPrimary,
      );
    },
  );
}
