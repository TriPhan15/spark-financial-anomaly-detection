import 'package:equatable/equatable.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:intl/intl.dart';

import 'package:finance_ai_app/models/app_language.dart';
import 'package:finance_ai_app/repositories/settings_repository.dart';

class LanguageState extends Equatable {
  const LanguageState(this.language);

  final AppLanguage language;

  Locale get locale => language.locale;

  @override
  List<Object?> get props => [language];
}

class LanguageCubit extends Cubit<LanguageState> {
  LanguageCubit(this._settings) : super(LanguageState(_settings.language)) {
    _applyIntlLocale(state.language);
  }

  final SettingsRepository _settings;

  /// Applies [language] at once (UI, Intl and the next API request), then saves it. Returns false when saving failed;
  /// the choice stays applied anyway.
  Future<bool> changeLanguage(AppLanguage language) async {
    if (language == state.language) return true;
    _applyIntlLocale(language);
    emit(LanguageState(language));
    return _settings.setLanguage(language);
  }

  static void _applyIntlLocale(AppLanguage language) {
    Intl.defaultLocale = language.intlLocale;
  }
}
