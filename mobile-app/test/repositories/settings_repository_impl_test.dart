import 'dart:ui' show PlatformDispatcher;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/models/app_language.dart';
import 'package:finance_ai_app/repositories/settings_repository_impl.dart';

import '../helpers/captured_logs.dart';

const String _themeKey = SettingsRepositoryImpl.themeModeKey;
const String _languageKey = SettingsRepositoryImpl.languageKey;

/// A device that cannot write: setString throws, or answers false when [refuses] is set. Nothing is stored on it.
class _FailingPreferences implements SharedPreferences {
  _FailingPreferences({this.refuses = false});

  final bool refuses;

  @override
  Object? get(String key) => null;

  @override
  Future<bool> setString(String key, String value) async {
    if (refuses) return false;
    throw PlatformException(code: 'write-failed', message: 'disk full');
  }

  @override
  dynamic noSuchMethod(Invocation invocation) {
    throw UnimplementedError(
      '_FailingPreferences does not fake ${invocation.memberName}',
    );
  }
}

void main() {
  Future<SharedPreferences> preferences([
    Map<String, Object> values = const {},
  ]) {
    SharedPreferences.setMockInitialValues(values);
    return SharedPreferences.getInstance();
  }

  SettingsRepositoryImpl repository(
    SharedPreferences preferences, {
    Locale device = const Locale('vi', 'VN'),
  }) => SettingsRepositoryImpl(preferences, deviceLocale: () => device);

  group('theme', () {
    test('follows the device theme when nothing was saved', () async {
      expect(repository(await preferences()).themeMode, ThemeMode.system);
    });

    test(
      'reads the saved theme, including the light and dark values saved before this version',
      () async {
        expect(
          repository(await preferences({_themeKey: 'light'})).themeMode,
          ThemeMode.light,
        );
        expect(
          repository(await preferences({_themeKey: 'dark'})).themeMode,
          ThemeMode.dark,
        );
        expect(
          repository(await preferences({_themeKey: 'system'})).themeMode,
          ThemeMode.system,
        );
      },
    );

    test(
      'ignores a saved value it does not know and follows the device',
      () async {
        expect(
          repository(await preferences({_themeKey: 'sepia'})).themeMode,
          ThemeMode.system,
        );
        expect(
          repository(await preferences({_themeKey: 1})).themeMode,
          ThemeMode.system,
        );
      },
    );

    test(
      'saves a new theme where the next start of the app reads it',
      () async {
        final prefs = await preferences();

        expect(await repository(prefs).setThemeMode(ThemeMode.dark), isTrue);

        expect(prefs.getString(_themeKey), 'dark');
        expect(repository(prefs).themeMode, ThemeMode.dark);
      },
    );
  });

  group('language', () {
    test(
      'follows the device language when nothing was saved and the device uses English or Vietnamese',
      () async {
        final prefs = await preferences();

        expect(
          repository(prefs, device: const Locale('en', 'GB')).language,
          AppLanguage.english,
        );
        expect(
          repository(prefs, device: const Locale('vi')).language,
          AppLanguage.vietnamese,
        );
      },
    );

    test('falls back to Vietnamese for any other device language', () async {
      final prefs = await preferences();

      expect(
        repository(prefs, device: const Locale('fr', 'FR')).language,
        AppLanguage.vietnamese,
      );
      expect(
        repository(prefs, device: const Locale('und')).language,
        AppLanguage.vietnamese,
      );
    });

    test(
      'reads the device language from PlatformDispatcher by default',
      () async {
        final device = PlatformDispatcher.instance.locale.languageCode;

        expect(
          SettingsRepositoryImpl(await preferences()).language,
          AppLanguage.fromLanguageCode(device) ?? AppLanguage.vietnamese,
        );
      },
    );

    test('a saved choice wins over the device language', () async {
      expect(
        repository(
          await preferences({_languageKey: 'en'}),
          device: const Locale('vi'),
        ).language,
        AppLanguage.english,
      );
    });

    test('ignores a saved language the app does not support', () async {
      expect(
        repository(
          await preferences({_languageKey: 'fr'}),
          device: const Locale('en'),
        ).language,
        AppLanguage.english,
      );
    });

    test('gives the language code and the Accept-Language tag', () async {
      final english = repository(await preferences({_languageKey: 'en'}));
      expect(english.languageCode, 'en');
      expect(english.localeTag, 'en-US');

      final vietnamese = repository(await preferences({_languageKey: 'vi'}));
      expect(vietnamese.languageCode, 'vi');
      expect(vietnamese.localeTag, 'vi-VN');
    });

    test(
      'saves a new language where the next start of the app reads it',
      () async {
        final prefs = await preferences();

        expect(
          await repository(prefs).setLanguage(AppLanguage.english),
          isTrue,
        );

        expect(prefs.getString(_languageKey), 'en');
        expect(
          repository(prefs, device: const Locale('vi')).language,
          AppLanguage.english,
        );
      },
    );
  });

  group('memory cache', () {
    test(
      'is filled when the repository is built and is not read back from storage',
      () async {
        final prefs = await preferences({
          _themeKey: 'light',
          _languageKey: 'vi',
        });
        final settings = repository(prefs);

        await prefs.setString(_themeKey, 'dark');
        await prefs.setString(_languageKey, 'en');

        expect(settings.themeMode, ThemeMode.light);
        expect(settings.localeTag, 'vi-VN');
      },
    );

    test('holds a new value before the write has finished', () async {
      final settings = repository(await preferences());

      final languageWrite = settings.setLanguage(AppLanguage.english);
      final themeWrite = settings.setThemeMode(ThemeMode.dark);

      expect(settings.language, AppLanguage.english);
      expect(settings.languageCode, 'en');
      expect(settings.localeTag, 'en-US');
      expect(settings.themeMode, ThemeMode.dark);
      expect(await languageWrite, isTrue);
      expect(await themeWrite, isTrue);
    });
  });

  group('when the device cannot save', () {
    test(
      'a failing write is logged and answered with false, and the new value stays in memory',
      () async {
        await withCapturedLogs((logs) async {
          final settings = SettingsRepositoryImpl(
            _FailingPreferences(),
            deviceLocale: () => const Locale('vi'),
          );

          expect(await settings.setThemeMode(ThemeMode.dark), isFalse);
          expect(await settings.setLanguage(AppLanguage.english), isFalse);

          expect(settings.themeMode, ThemeMode.dark);
          expect(settings.localeTag, 'en-US');
          expect(logs, hasLength(2));
          expect(logs[0], contains(_themeKey));
          expect(logs[1], contains(_languageKey));
        });
      },
    );

    test('a refused write is answered with false as well', () async {
      await withCapturedLogs((logs) async {
        final settings = SettingsRepositoryImpl(
          _FailingPreferences(refuses: true),
          deviceLocale: () => const Locale('vi'),
        );

        expect(await settings.setThemeMode(ThemeMode.light), isFalse);

        expect(settings.themeMode, ThemeMode.light);
        expect(logs.single, contains(_themeKey));
      });
    });
  });
}
