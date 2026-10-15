import 'package:flutter/material.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/l10n/app_localizations.dart';

extension BuildContextExtension on BuildContext {
  AppLocalizations get loc {
    final localizations = Localizations.of<AppLocalizations>(
      this,
      AppLocalizations,
    );
    if (localizations == null) {
      throw FlutterError(
        'context.loc found no AppLocalizations: pass AppLocalizations.localizationsDelegates to the MaterialApp.',
      );
    }
    return localizations;
  }

  /// The fintech colours of the current theme; see [ThemeDataSemanticColors.semanticColors].
  SemanticColors get colors => Theme.of(this).semanticColors;
}

extension ThemeDataSemanticColors on ThemeData {
  /// The fintech colours of this theme. A theme without [SemanticColors] gets the default palette of its brightness,
  /// so a dark screen never shows the light colours. Lets a build read the colour scheme and these colours from one
  /// `Theme.of(context)`.
  SemanticColors get semanticColors =>
      extension<SemanticColors>() ??
      (colorScheme.brightness == Brightness.dark
          ? const SemanticColors.defaultDark()
          : const SemanticColors.defaultLight());
}
