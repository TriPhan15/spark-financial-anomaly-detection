import 'package:flutter/material.dart';

import 'package:finance_ai_app/models/app_language.dart';

/// App settings kept on the device. Reads are synchronous and served from memory, so they are safe on hot paths such
/// as a Dio interceptor.
abstract class SettingsRepository {
  ThemeMode get themeMode;

  AppLanguage get language;

  String get languageCode;

  String get localeTag;

  /// Updates the value in memory at once, then writes it to the device. Returns false when the write failed: the new
  /// value still applies until the app is closed.
  Future<bool> setThemeMode(ThemeMode mode);

  /// Same contract as [setThemeMode].
  Future<bool> setLanguage(AppLanguage language);
}
