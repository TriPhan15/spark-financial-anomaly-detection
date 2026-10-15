import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import 'package:finance_ai_app/core/extensions/build_context_extension.dart';
import 'package:finance_ai_app/cubits/language_cubit.dart';
import 'package:finance_ai_app/cubits/theme_cubit.dart';
import 'package:finance_ai_app/l10n/app_localizations.dart';
import 'package:finance_ai_app/models/app_language.dart';

extension SettingsLabels on AppLocalizations {
  String themeModeLabel(ThemeMode mode) => switch (mode) {
    ThemeMode.light => light,
    ThemeMode.dark => dark,
    ThemeMode.system => system,
  };

  String languageLabel(AppLanguage language) => switch (language) {
    AppLanguage.vietnamese => vietnamese,
    AppLanguage.english => english,
  };
}

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(context.loc.settings)),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: const [
            _ThemeSection(),
            SizedBox(height: 24),
            _LanguageSection(),
          ],
        ),
      ),
    );
  }
}

void _showSaveFailed(BuildContext context) {
  ScaffoldMessenger.of(
    context,
  ).showSnackBar(SnackBar(content: Text(context.loc.settingsSaveFailed)));
}

class _ThemeSection extends StatelessWidget {
  const _ThemeSection();

  static const List<ThemeMode> _options = [
    ThemeMode.light,
    ThemeMode.dark,
    ThemeMode.system,
  ];

  static IconData _iconOf(ThemeMode mode) => switch (mode) {
    ThemeMode.light => Icons.light_mode_rounded,
    ThemeMode.dark => Icons.dark_mode_rounded,
    ThemeMode.system => Icons.brightness_auto_rounded,
  };

  Future<void> _select(BuildContext context, ThemeMode mode) async {
    final saved = await context.read<ThemeCubit>().changeThemeMode(mode);
    if (!context.mounted) return;
    if (!saved) _showSaveFailed(context);
  }

  @override
  Widget build(BuildContext context) {
    final loc = context.loc;
    return BlocSelector<ThemeCubit, ThemeState, ThemeMode>(
      selector: (state) => state.themeMode,
      builder: (context, selected) => _SettingsSection(
        icon: Icons.palette_rounded,
        title: loc.theme,
        children: [
          for (final mode in _options)
            _SettingsOptionTile(
              leading: Icon(_iconOf(mode)),
              label: loc.themeModeLabel(mode),
              subtitle: mode == ThemeMode.system
                  ? loc.systemThemeDescription
                  : null,
              selected: mode == selected,
              onTap: () => _select(context, mode),
            ),
        ],
      ),
    );
  }
}

class _LanguageSection extends StatelessWidget {
  const _LanguageSection();

  static String _flagOf(AppLanguage language) => switch (language) {
    AppLanguage.vietnamese => '🇻🇳',
    AppLanguage.english => '🇺🇸',
  };

  Future<void> _select(BuildContext context, AppLanguage language) async {
    final saved = await context.read<LanguageCubit>().changeLanguage(language);
    if (!context.mounted) return;
    if (!saved) _showSaveFailed(context);
  }

  @override
  Widget build(BuildContext context) {
    final loc = context.loc;
    return BlocSelector<LanguageCubit, LanguageState, AppLanguage>(
      selector: (state) => state.language,
      builder: (context, selected) => _SettingsSection(
        icon: Icons.language_rounded,
        title: loc.language,
        children: [
          for (final language in AppLanguage.values)
            _SettingsOptionTile(
              leading: Text(
                _flagOf(language),
                style: const TextStyle(fontSize: 24),
              ),
              label: loc.languageLabel(language),
              selected: language == selected,
              onTap: () => _select(context, language),
            ),
        ],
      ),
    );
  }
}

class _SettingsSection extends StatelessWidget {
  const _SettingsSection({
    required this.icon,
    required this.title,
    required this.children,
  });

  final IconData icon;
  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: 8, left: 4),
          child: Row(
            children: [
              Icon(icon, color: colorScheme.primary, size: 17),
              const SizedBox(width: 6),
              Semantics(
                header: true,
                child: Text(
                  title,
                  style: TextStyle(
                    color: colorScheme.onSurface,
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ],
          ),
        ),
        Material(
          color: colorScheme.surface,
          borderRadius: BorderRadius.circular(16),
          clipBehavior: Clip.antiAlias,
          child: Column(
            children: [
              for (var i = 0; i < children.length; i++) ...[
                if (i > 0) const Divider(height: 1, indent: 60, endIndent: 16),
                children[i],
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _SettingsOptionTile extends StatelessWidget {
  const _SettingsOptionTile({
    required this.leading,
    required this.label,
    required this.selected,
    required this.onTap,
    this.subtitle,
  });

  final Widget leading;
  final String label;
  final String? subtitle;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final subtitle = this.subtitle;
    return ListTile(
      leading: leading,
      title: Text(
        label,
        style: TextStyle(
          fontSize: 14,
          fontWeight: selected ? FontWeight.bold : FontWeight.normal,
        ),
      ),
      subtitle: subtitle == null
          ? null
          : Text(subtitle, style: const TextStyle(fontSize: 12)),
      trailing: selected
          ? Icon(Icons.check_rounded, color: colorScheme.primary)
          : null,
      selected: selected,
      selectedColor: colorScheme.primary,
      iconColor: colorScheme.onSurfaceVariant,
      textColor: colorScheme.onSurface,
      onTap: onTap,
    );
  }
}
