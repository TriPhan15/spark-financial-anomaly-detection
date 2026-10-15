import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/cubits/theme_cubit.dart';

import '../helpers/fake_settings_repository.dart';

void main() {
  ThemeCubit cubitFor(FakeSettingsRepository settings) {
    final cubit = ThemeCubit(settings);
    addTearDown(cubit.close);
    return cubit;
  }

  test(
    'starts from the theme in the settings, the system theme by default',
    () {
      expect(
        cubitFor(FakeSettingsRepository()).state,
        const ThemeState(ThemeMode.system),
      );
      expect(
        cubitFor(FakeSettingsRepository(themeMode: ThemeMode.dark)).state,
        const ThemeState(ThemeMode.dark),
      );
    },
  );

  test(
    'shows the new theme before the write has finished, then saves it',
    () async {
      final settings = FakeSettingsRepository()
        ..pendingSave = Completer<bool>();
      final cubit = cubitFor(settings);
      final states = <ThemeState>[];
      final subscription = cubit.stream.listen(states.add);
      addTearDown(subscription.cancel);

      final change = cubit.changeThemeMode(ThemeMode.dark);

      expect(cubit.state, const ThemeState(ThemeMode.dark));
      expect(settings.themeMode, ThemeMode.dark);
      settings.pendingSave!.complete(true);
      expect(await change, isTrue);
      expect(states, [const ThemeState(ThemeMode.dark)]);
      expect(settings.saved, [ThemeMode.dark]);
    },
  );

  test('picking the current theme again neither emits nor saves', () async {
    final settings = FakeSettingsRepository(themeMode: ThemeMode.light);
    final cubit = cubitFor(settings);
    final states = <ThemeState>[];
    final subscription = cubit.stream.listen(states.add);
    addTearDown(subscription.cancel);

    expect(await cubit.changeThemeMode(ThemeMode.light), isTrue);

    expect(states, isEmpty);
    expect(settings.saved, isEmpty);
  });

  test('a failed save keeps the new theme and answers false', () async {
    final cubit = cubitFor(FakeSettingsRepository(saveResult: false));

    expect(await cubit.changeThemeMode(ThemeMode.dark), isFalse);

    expect(cubit.state, const ThemeState(ThemeMode.dark));
  });

  test('two states with the same theme are equal', () {
    expect(const ThemeState(ThemeMode.dark), const ThemeState(ThemeMode.dark));
    expect(
      const ThemeState(ThemeMode.dark),
      isNot(const ThemeState(ThemeMode.light)),
    );
    expect(const ThemeState(ThemeMode.dark).props, [ThemeMode.dark]);
  });
}
