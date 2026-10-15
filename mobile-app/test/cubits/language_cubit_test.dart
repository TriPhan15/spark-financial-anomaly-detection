import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';

import 'package:finance_ai_app/cubits/language_cubit.dart';
import 'package:finance_ai_app/models/app_language.dart';

import '../helpers/fake_settings_repository.dart';

void main() {
  tearDown(() => Intl.defaultLocale = null);

  LanguageCubit cubitFor(FakeSettingsRepository settings) {
    final cubit = LanguageCubit(settings);
    addTearDown(cubit.close);
    return cubit;
  }

  test(
    'starts from the language in the settings and sets Intl.defaultLocale to it',
    () {
      final english = cubitFor(
        FakeSettingsRepository(language: AppLanguage.english),
      );
      expect(english.state.language, AppLanguage.english);
      expect(english.state.locale, const Locale('en'));
      expect(Intl.defaultLocale, 'en_US');

      final vietnamese = cubitFor(FakeSettingsRepository());
      expect(vietnamese.state.locale, const Locale('vi'));
      expect(Intl.defaultLocale, 'vi_VN');
    },
  );

  test(
    'applies a new language to the UI, to Intl and to the next request before the write has finished',
    () async {
      final settings = FakeSettingsRepository()
        ..pendingSave = Completer<bool>();
      final cubit = cubitFor(settings);
      final states = <LanguageState>[];
      final subscription = cubit.stream.listen(states.add);
      addTearDown(subscription.cancel);

      final change = cubit.changeLanguage(AppLanguage.english);

      expect(cubit.state.locale, const Locale('en'));
      expect(Intl.defaultLocale, 'en_US');
      expect(settings.localeTag, 'en-US');
      settings.pendingSave!.complete(true);
      expect(await change, isTrue);
      expect(states, [const LanguageState(AppLanguage.english)]);
      expect(settings.saved, [AppLanguage.english]);
    },
  );

  test('picking the current language again neither emits nor saves', () async {
    final settings = FakeSettingsRepository();
    final cubit = cubitFor(settings);
    final states = <LanguageState>[];
    final subscription = cubit.stream.listen(states.add);
    addTearDown(subscription.cancel);

    expect(await cubit.changeLanguage(AppLanguage.vietnamese), isTrue);

    expect(states, isEmpty);
    expect(settings.saved, isEmpty);
  });

  test('a failed save keeps the new language and answers false', () async {
    final cubit = cubitFor(FakeSettingsRepository(saveResult: false));

    expect(await cubit.changeLanguage(AppLanguage.english), isFalse);

    expect(cubit.state.language, AppLanguage.english);
    expect(Intl.defaultLocale, 'en_US');
  });

  test('two states with the same language are equal', () {
    expect(
      const LanguageState(AppLanguage.english),
      const LanguageState(AppLanguage.english),
    );
    expect(
      const LanguageState(AppLanguage.english),
      isNot(const LanguageState(AppLanguage.vietnamese)),
    );
    expect(const LanguageState(AppLanguage.english).props, [
      AppLanguage.english,
    ]);
  });
}
