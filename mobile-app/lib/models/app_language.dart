import 'dart:ui' show Locale;

enum AppLanguage {
  vietnamese('vi', 'vi_VN', 'vi-VN'),
  english('en', 'en_US', 'en-US');

  const AppLanguage(this.languageCode, this.intlLocale, this.localeTag);

  final String languageCode;
  final String intlLocale;
  final String localeTag;

  static const AppLanguage fallback = AppLanguage.vietnamese;

  Locale get locale => Locale(languageCode);

  static AppLanguage? fromLanguageCode(String? languageCode) {
    for (final language in values) {
      if (language.languageCode == languageCode) return language;
    }
    return null;
  }
}
