import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/di/injection_container.dart';
import 'package:finance_ai_app/repositories/settings_repository.dart';
import 'package:finance_ai_app/repositories/settings_repository_impl.dart';

void main() {
  tearDown(() => sl.reset());

  test(
    'registers the settings under the SettingsRepository interface, with the saved values already in memory',
    () async {
      SharedPreferences.setMockInitialValues({
        SettingsRepositoryImpl.themeModeKey: 'dark',
        SettingsRepositoryImpl.languageKey: 'en',
      });

      await configureDependencies();

      expect(sl.isRegistered<SettingsRepository>(), isTrue);
      expect(sl.isRegistered<SettingsRepositoryImpl>(), isFalse);
      final settings = sl<SettingsRepository>();
      expect(settings.themeMode, ThemeMode.dark);
      expect(settings.languageCode, 'en');
      expect(settings.localeTag, 'en-US');
    },
  );

  test('a second call keeps the registered repository', () async {
    SharedPreferences.setMockInitialValues({});
    await configureDependencies();
    final first = sl<SettingsRepository>();

    await configureDependencies();

    expect(sl<SettingsRepository>(), same(first));
  });

  test('two calls at the same time register one repository', () async {
    SharedPreferences.setMockInitialValues({});

    await Future.wait([configureDependencies(), configureDependencies()]);

    expect(sl.isRegistered<SettingsRepository>(), isTrue);
  });
}
