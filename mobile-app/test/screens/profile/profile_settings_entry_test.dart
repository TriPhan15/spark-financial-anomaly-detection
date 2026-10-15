import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/models/app_language.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/profile/profile_page.dart';
import 'package:finance_ai_app/screens/profile/settings_screen.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../../helpers/app_settings_scope.dart';
import '../../helpers/fake_backend.dart';
import '../../helpers/fake_local_store.dart';
import '../../helpers/fake_settings_repository.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final backend = FakeBackend();

  setUpAll(() {
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
    AppRepository.instance.localStore = FakeLocalStore();
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

  tearDown(() => Intl.defaultLocale = null);

  Future<void> pumpProfile(
    WidgetTester tester,
    FakeSettingsRepository settings,
  ) async {
    tester.view.physicalSize = const Size(600, 3000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      withAppSettings(const ProfilePage(), settings: settings),
    );
    await tester.pumpAndSettle();
    await tester.pump(const Duration(milliseconds: 100));
  }

  testWidgets('the language and theme rows show the current choices', (
    tester,
  ) async {
    await pumpProfile(tester, FakeSettingsRepository());

    expect(find.text('Ngôn ngữ'), findsOneWidget);
    expect(find.text('Tiếng Việt'), findsOneWidget);
    expect(find.text('Giao diện'), findsOneWidget);
    expect(find.text('Theo hệ thống'), findsOneWidget);
  });

  testWidgets('the two rows are translated when the app is in English', (
    tester,
  ) async {
    await pumpProfile(
      tester,
      FakeSettingsRepository(
        language: AppLanguage.english,
        themeMode: ThemeMode.dark,
      ),
    );

    expect(find.text('Language'), findsOneWidget);
    expect(find.text('English'), findsOneWidget);
    expect(find.text('Theme'), findsOneWidget);
    expect(find.text('Dark'), findsOneWidget);
  });

  testWidgets(
    'both rows open the settings screen, and a choice made there shows on the row after going back',
    (tester) async {
      await pumpProfile(tester, FakeSettingsRepository());

      await tester.tap(find.text('Giao diện'));
      await tester.pumpAndSettle();
      expect(find.byType(SettingsScreen), findsOneWidget);

      await tester.tap(find.text('Tối'));
      await tester.pumpAndSettle();
      // pageBack() looks for the English "Back" tooltip; in Vietnamese the button says "Quay lại".
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();

      expect(find.byType(SettingsScreen), findsNothing);
      expect(find.text('Tối'), findsOneWidget);
      expect(find.text('Theo hệ thống'), findsNothing);

      await tester.tap(find.text('Ngôn ngữ'));
      await tester.pumpAndSettle();
      expect(find.byType(SettingsScreen), findsOneWidget);
    },
  );
}
