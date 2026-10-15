import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/di/injection_container.dart';
import 'package:finance_ai_app/main.dart';
import 'package:finance_ai_app/repositories/settings_repository.dart';
import 'package:finance_ai_app/repositories/settings_repository_impl.dart';
import 'package:finance_ai_app/screens/auth/login_page.dart';
import 'package:finance_ai_app/screens/profile/settings_screen.dart';

import '../../helpers/fake_settings_repository.dart';

void main() {
  tearDown(() async {
    await sl.reset();
    Intl.defaultLocale = null;
  });

  /// The app as main() builds it (signed out, so the login screen), with the settings screen pushed on top.
  Future<void> openSettings(WidgetTester tester) async {
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(const DonutPayApp());
    await tester.pumpAndSettle();
    navigatorKey.currentState!.push(
      MaterialPageRoute<void>(builder: (_) => const SettingsScreen()),
    );
    await tester.pumpAndSettle();
  }

  ListTile tileOf(WidgetTester tester, String label) => tester.widget<ListTile>(
    find.ancestor(of: find.text(label), matching: find.byType(ListTile)),
  );

  Brightness screenBrightness(WidgetTester tester) =>
      Theme.of(tester.element(find.byType(SettingsScreen))).brightness;

  MaterialApp materialApp(WidgetTester tester) =>
      tester.widget<MaterialApp>(find.byType(MaterialApp));

  group('when the device saves the choices', () {
    setUp(() async {
      SharedPreferences.setMockInitialValues({
        SettingsRepositoryImpl.languageKey: 'vi',
      });
      await configureDependencies();
      // A Future made here and awaited inside testWidgets never completes, so the test body gets its own instance.
      SharedPreferences.setMockInitialValues({});
    });

    testWidgets('lists every option in Vietnamese and marks the current ones', (
      tester,
    ) async {
      await openSettings(tester);

      for (final label in [
        'Cài đặt',
        'Giao diện',
        'Sáng',
        'Tối',
        'Theo hệ thống',
        'Tự đổi theo cài đặt của thiết bị',
        'Ngôn ngữ',
        'Tiếng Việt',
        'English',
      ]) {
        expect(find.text(label), findsOneWidget, reason: label);
      }
      expect(tileOf(tester, 'Theo hệ thống').selected, isTrue);
      expect(tileOf(tester, 'Sáng').selected, isFalse);
      expect(tileOf(tester, 'Tối').selected, isFalse);
      expect(tileOf(tester, 'Tiếng Việt').selected, isTrue);
      expect(tileOf(tester, 'English').selected, isFalse);
      expect(find.byIcon(Icons.check_rounded), findsNWidgets(2));
    });

    testWidgets(
      'choosing Dark turns the app dark at once, marks it and saves it without leaving the screen',
      (tester) async {
        await openSettings(tester);
        expect(screenBrightness(tester), Brightness.light);

        await tester.tap(find.text('Tối'));
        await tester.pumpAndSettle();

        expect(materialApp(tester).themeMode, ThemeMode.dark);
        expect(screenBrightness(tester), Brightness.dark);
        expect(tileOf(tester, 'Tối').selected, isTrue);
        expect(tileOf(tester, 'Theo hệ thống').selected, isFalse);
        expect(sl<SettingsRepository>().themeMode, ThemeMode.dark);
        final prefs = await SharedPreferences.getInstance();
        expect(prefs.getString(SettingsRepositoryImpl.themeModeKey), 'dark');
        expect(find.byType(SettingsScreen), findsOneWidget);
      },
    );

    testWidgets(
      'choosing Light keeps a light app even when the device is in dark mode',
      (tester) async {
        tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
        addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
        await openSettings(tester);
        expect(
          screenBrightness(tester),
          Brightness.dark,
          reason: 'with nothing saved the app follows the device',
        );
        expect(tileOf(tester, 'Theo hệ thống').selected, isTrue);

        await tester.tap(find.text('Sáng'));
        await tester.pumpAndSettle();

        expect(screenBrightness(tester), Brightness.light);
        expect(tileOf(tester, 'Sáng').selected, isTrue);
        final prefs = await SharedPreferences.getInstance();
        expect(prefs.getString(SettingsRepositoryImpl.themeModeKey), 'light');
      },
    );

    testWidgets(
      'the status and navigation bar icons follow the theme, also on screens without an app bar',
      (tester) async {
        await openSettings(tester);
        final rootRegion = find.ancestor(
          of: find.byType(Navigator),
          matching: find.byType(AnnotatedRegion<SystemUiOverlayStyle>),
        );
        expect(rootRegion, findsOneWidget);
        expect(
          tester
              .widget<AnnotatedRegion<SystemUiOverlayStyle>>(rootRegion)
              .value,
          AppTheme.lightOverlayStyle,
        );

        await tester.tap(find.text('Tối'));
        await tester.pumpAndSettle();

        expect(
          tester
              .widget<AnnotatedRegion<SystemUiOverlayStyle>>(rootRegion)
              .value,
          AppTheme.darkOverlayStyle,
        );
        expect(
          SystemChrome.latestStyle?.statusBarIconBrightness,
          Brightness.light,
        );
        expect(
          SystemChrome.latestStyle?.systemNavigationBarIconBrightness,
          Brightness.light,
        );
      },
    );

    testWidgets(
      'choosing English translates the screen at once and sets the language of the next requests',
      (tester) async {
        await openSettings(tester);

        await tester.tap(find.text('English'));
        await tester.pumpAndSettle();

        for (final label in [
          'Settings',
          'Theme',
          'Light',
          'Dark',
          'System',
          'Follows your device setting',
          'Language',
          'Tiếng Việt',
          'English',
        ]) {
          expect(find.text(label), findsOneWidget, reason: label);
        }
        expect(find.text('Cài đặt'), findsNothing);
        expect(tileOf(tester, 'English').selected, isTrue);
        expect(tileOf(tester, 'Tiếng Việt').selected, isFalse);
        expect(materialApp(tester).locale, const Locale('en'));
        expect(Intl.defaultLocale, 'en_US');
        expect(sl<SettingsRepository>().localeTag, 'en-US');
        final prefs = await SharedPreferences.getInstance();
        expect(prefs.getString(SettingsRepositoryImpl.languageKey), 'en');
        expect(find.byType(SettingsScreen), findsOneWidget);
      },
    );

    testWidgets(
      'going back after a change returns to the same screen as before',
      (tester) async {
        await openSettings(tester);
        await tester.tap(find.text('Tối'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('English'));
        await tester.pumpAndSettle();

        await tester.pageBack();
        await tester.pumpAndSettle();

        expect(find.byType(SettingsScreen), findsNothing);
        expect(find.byType(LoginScreen), findsOneWidget);
      },
    );
  });

  group('when the device cannot save', () {
    setUp(() {
      sl.registerSingleton<SettingsRepository>(
        FakeSettingsRepository(saveResult: false),
      );
    });

    testWidgets(
      'the new theme stays applied and a snackbar says it was not saved',
      (tester) async {
        await openSettings(tester);

        await tester.tap(find.text('Tối'));
        await tester.pumpAndSettle();

        expect(
          find.text(
            'Không lưu được cài đặt. Thay đổi chỉ có hiệu lực đến khi đóng ứng dụng.',
          ),
          findsOneWidget,
        );
        expect(tileOf(tester, 'Tối').selected, isTrue);
        expect(screenBrightness(tester), Brightness.dark);
      },
    );
  });
}
