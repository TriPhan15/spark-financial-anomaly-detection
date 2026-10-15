import 'dart:async';

import 'package:flutter/material.dart';

import 'package:finance_ai_app/models/app_language.dart';
import 'package:finance_ai_app/repositories/settings_repository.dart';

/// Keeps the settings in memory. Every write answers [saveResult], or waits for [pendingSave] when a test sets it.
class FakeSettingsRepository implements SettingsRepository {
  FakeSettingsRepository({
    this.themeMode = ThemeMode.system,
    this.language = AppLanguage.vietnamese,
    this.saveResult = true,
  });

  @override
  ThemeMode themeMode;

  @override
  AppLanguage language;

  bool saveResult;
  Completer<bool>? pendingSave;
  int localeTagReads = 0;
  final List<Object> saved = [];

  @override
  String get languageCode => language.languageCode;

  @override
  String get localeTag {
    localeTagReads++;
    return language.localeTag;
  }

  @override
  Future<bool> setThemeMode(ThemeMode mode) {
    themeMode = mode;
    return _save(mode);
  }

  @override
  Future<bool> setLanguage(AppLanguage language) {
    this.language = language;
    return _save(language);
  }

  Future<bool> _save(Object value) {
    saved.add(value);
    return pendingSave?.future ?? Future.value(saveResult);
  }
}
