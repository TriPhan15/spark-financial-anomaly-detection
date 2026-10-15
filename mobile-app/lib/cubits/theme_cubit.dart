import 'package:equatable/equatable.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import 'package:finance_ai_app/repositories/settings_repository.dart';

class ThemeState extends Equatable {
  const ThemeState(this.themeMode);

  final ThemeMode themeMode;

  @override
  List<Object?> get props => [themeMode];
}

class ThemeCubit extends Cubit<ThemeState> {
  ThemeCubit(this._settings) : super(ThemeState(_settings.themeMode));

  final SettingsRepository _settings;

  /// Applies [mode] at once, then saves it. Returns false when saving failed; the choice stays applied anyway.
  Future<bool> changeThemeMode(ThemeMode mode) async {
    if (mode == state.themeMode) return true;
    emit(ThemeState(mode));
    return _settings.setThemeMode(mode);
  }
}
