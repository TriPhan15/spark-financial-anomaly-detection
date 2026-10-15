import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';

class AppTheme {
  static const Color primary = Color(0xFF4A2545);
  static const Color primaryDark = Color(0xFF2E1729);
  static const Color gradientStart = Color(0xFF5C2E54);
  static const Color gradientEnd = Color(0xFF2E1729);

  static const Color cardDarkGradStart = Color(0xFF2B2B3A);
  static const Color cardDarkGradEnd = Color(0xFF16161F);

  static const Color bgLight = Color(0xFFF4F4F6);
  static const Color bgDark = Color(0xFFF4F4F6);
  static const Color navyDark = Color(0xFFFFFFFF);
  static const Color navyLight = Color(0xFFFFFFFF);
  static const Color navyMid = Color(0xFFFFFFFF);
  static const Color cardDark = Color(0xFFFFFFFF);

  static const Color mintAccent = primary;
  static const Color mintLight = Color(0xFFEFE9EE);

  static const Color textWhite = Color(0xFF1F1B24);
  static const Color textGray = Color(0xFF9A9AA0);
  static const Color textPrimary = Color(0xFF1F1B24);
  static const Color textSecondary = Color(0xFF9A9AA0);

  static const Color dangerColor = Color(0xFFE0457B);
  static const Color warningColor = Colors.orange;
  static const Color positive = Color(0xFF3DBE8B);
  static const Color negative = Color(0xFFE0457B);

  static const Color _lightSurface = Colors.white;
  static const Color _lightBorder = Color(0xFFE7E7EC);

  // Neutral greys for the light container roles (tracks, skeletons, tooltips): the seed would tint them pink, which
  // the light screens never were. The highest one is the old track colour, textGray at 20% on white.
  static const Color _lightContainerLow = Color(0xFFF9F9FA);
  static const Color _lightContainerHigh = Color(0xFFEFEFF1);
  static const Color _lightContainerHighest = Color(0xFFEBEBEC);

  // Dark mode has its own tones and never falls back on the light-only colours above.
  static const Color _darkBackground = Color(0xFF141117);
  static const Color _darkSurface = Color(0xFF1F1B24);
  static const Color _darkTextPrimary = Color(0xFFF4F4F6);
  static const Color _darkTextSecondary = Color(0xFFB3ADB9);
  static const Color _darkBorder = Color(0xFF4A4351);
  static const Color _darkPrimary = Color(0xFFF2B3E5);
  static const Color _darkOnPrimary = Color(0xFF4C1F48);
  static const Color _darkPrimaryContainer = Color(0xFF66355F);
  static const Color _darkOnPrimaryContainer = Color(0xFFFFD7F4);
  static const Color _darkPositive = Color(0xFF5CD6A4);
  static const Color _darkNegative = Color(0xFFFF7AA2);
  static const Color _darkOnNegative = Color(0xFF4A0A24);
  static const Color _darkWarning = Color(0xFFFFB74D);

  static const SystemUiOverlayStyle lightOverlayStyle = SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.dark,
    statusBarBrightness: Brightness.light,
    systemNavigationBarColor: _lightSurface,
    systemNavigationBarIconBrightness: Brightness.dark,
    systemNavigationBarDividerColor: Colors.transparent,
  );

  static const SystemUiOverlayStyle darkOverlayStyle = SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
    statusBarBrightness: Brightness.dark,
    systemNavigationBarColor: _darkSurface,
    systemNavigationBarIconBrightness: Brightness.light,
    systemNavigationBarDividerColor: Colors.transparent,
  );

  static SystemUiOverlayStyle overlayStyleFor(Brightness brightness) =>
      brightness == Brightness.dark ? darkOverlayStyle : lightOverlayStyle;

  static const TextStyle _tabularFigures = TextStyle(
    fontFeatures: [FontFeature.tabularFigures()],
  );

  static const TextTheme _tabularTypography = TextTheme(
    displayLarge: _tabularFigures,
    displayMedium: _tabularFigures,
    displaySmall: _tabularFigures,
    headlineLarge: _tabularFigures,
    headlineMedium: _tabularFigures,
    headlineSmall: _tabularFigures,
    titleLarge: _tabularFigures,
    titleMedium: _tabularFigures,
    titleSmall: _tabularFigures,
    bodyLarge: _tabularFigures,
    bodyMedium: _tabularFigures,
    bodySmall: _tabularFigures,
    labelLarge: _tabularFigures,
    labelMedium: _tabularFigures,
    labelSmall: _tabularFigures,
  );

  static List<BoxShadow> get softShadow => [
    BoxShadow(
      color: Colors.black.withValues(alpha: 0.06),
      blurRadius: 16,
      offset: const Offset(0, 6),
    ),
  ];

  /// [softShadow] on a light surface; nothing on a dark one, where a black shadow does not show.
  static List<BoxShadow>? softShadowFor(ColorScheme colors) =>
      colors.brightness == Brightness.light ? softShadow : null;

  static BoxDecoration get card => BoxDecoration(
    color: navyLight,
    borderRadius: BorderRadius.circular(20),
    boxShadow: softShadow,
  );

  static const LinearGradient purpleCardGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [gradientStart, gradientEnd],
  );

  static const LinearGradient darkCardGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [cardDarkGradStart, cardDarkGradEnd],
  );

  /// Theme-aware version of [card], without its shadow: the surface colour alone sets the card apart, white on the
  /// grey light background and lighter than the dark one. Every caller is a plain Container, so the colour stays.
  static BoxDecoration cardDecoration(ColorScheme colors) => BoxDecoration(
    color: colors.surface,
    borderRadius: BorderRadius.circular(20),
  );

  // The light roles repeat the legacy constants, so a screen moved from AppTheme.* to the colour scheme looks the same
  // in light mode.
  static final ThemeData lightTheme = _buildTheme(
    colorScheme:
        ColorScheme.fromSeed(
          seedColor: primary,
          brightness: Brightness.light,
        ).copyWith(
          primary: primary,
          onPrimary: Colors.white,
          primaryContainer: mintLight,
          onPrimaryContainer: primary,
          secondary: primary,
          onSecondary: Colors.white,
          surface: _lightSurface,
          surfaceContainerLowest: _lightSurface,
          surfaceContainerLow: _lightContainerLow,
          surfaceContainer: bgLight,
          surfaceContainerHigh: _lightContainerHigh,
          surfaceContainerHighest: _lightContainerHighest,
          onSurface: textPrimary,
          onSurfaceVariant: textSecondary,
          outlineVariant: _lightBorder,
          error: dangerColor,
          onError: Colors.white,
        ),
    background: bgLight,
    overlayStyle: lightOverlayStyle,
    semanticColors: SemanticColors.light,
  );

  static final ThemeData darkTheme = _buildTheme(
    colorScheme:
        ColorScheme.fromSeed(
          seedColor: primary,
          brightness: Brightness.dark,
        ).copyWith(
          primary: _darkPrimary,
          onPrimary: _darkOnPrimary,
          primaryContainer: _darkPrimaryContainer,
          onPrimaryContainer: _darkOnPrimaryContainer,
          secondary: _darkPrimary,
          onSecondary: _darkOnPrimary,
          surface: _darkSurface,
          onSurface: _darkTextPrimary,
          onSurfaceVariant: _darkTextSecondary,
          outlineVariant: _darkBorder,
          error: _darkNegative,
          onError: _darkOnNegative,
        ),
    background: _darkBackground,
    overlayStyle: darkOverlayStyle,
    semanticColors: SemanticColors.dark,
  );

  static ThemeData _buildTheme({
    required ColorScheme colorScheme,
    required Color background,
    required SystemUiOverlayStyle overlayStyle,
    required SemanticColors semanticColors,
  }) {
    final base = ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: background,
      extensions: [semanticColors],
    );
    final text = colorScheme.onSurface;
    final border = BorderSide(color: colorScheme.outlineVariant);
    return base.copyWith(
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.android: FadeUpwardsPageTransitionsBuilder(),
          TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
          TargetPlatform.linux: FadeUpwardsPageTransitionsBuilder(),
          TargetPlatform.windows: FadeUpwardsPageTransitionsBuilder(),
          TargetPlatform.macOS: CupertinoPageTransitionsBuilder(),
        },
      ),
      textTheme: GoogleFonts.poppinsTextTheme(
        base.textTheme,
      ).apply(bodyColor: text, displayColor: text).merge(_tabularTypography),
      primaryTextTheme: base.primaryTextTheme.merge(_tabularTypography),
      iconTheme: IconThemeData(color: text),
      appBarTheme: AppBarTheme(
        backgroundColor: background,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: true,
        systemOverlayStyle: overlayStyle,
        iconTheme: IconThemeData(color: text),
        titleTextStyle: TextStyle(
          color: text,
          fontSize: 18,
          fontWeight: FontWeight.w600,
        ),
      ),
      cardTheme: CardThemeData(
        color: colorScheme.surface,
        elevation: 0,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        margin: EdgeInsets.zero,
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: colorScheme.surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: colorScheme.surface,
        modalBackgroundColor: colorScheme.surface,
        surfaceTintColor: Colors.transparent,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
      ),
      dividerTheme: DividerThemeData(
        color: colorScheme.outlineVariant,
        thickness: 1,
      ),
      bottomAppBarTheme: BottomAppBarThemeData(
        color: colorScheme.surface,
        surfaceTintColor: Colors.transparent,
      ),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        backgroundColor: colorScheme.primary,
        foregroundColor: colorScheme.onPrimary,
      ),
      // The pickers keep the look the app gave them before: the surface colour with the headline in the text colour.
      datePickerTheme: DatePickerThemeData(
        backgroundColor: colorScheme.surface,
        surfaceTintColor: Colors.transparent,
        headerForegroundColor: colorScheme.onSurface,
      ),
      bottomNavigationBarTheme: BottomNavigationBarThemeData(
        backgroundColor: colorScheme.surface,
        selectedItemColor: colorScheme.primary,
        unselectedItemColor: colorScheme.onSurfaceVariant,
        type: BottomNavigationBarType.fixed,
        elevation: 0,
        showUnselectedLabels: true,
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: colorScheme.primary,
          foregroundColor: colorScheme.onPrimary,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
          elevation: 0,
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: colorScheme.surface,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: border,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: border,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: colorScheme.primary, width: 2),
        ),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 16,
        ),
      ),
    );
  }
}

/// Fintech colours that the [ColorScheme] has no role for. Errors use [ColorScheme.error].
class SemanticColors extends ThemeExtension<SemanticColors> {
  /// [successColor] and [warningColor] default to the light palette, so that two-colour instances keep compiling.
  const SemanticColors({
    required this.incomeColor,
    required this.expenseColor,
    this.successColor = AppTheme.positive,
    this.warningColor = AppTheme.warningColor,
  });

  const SemanticColors.defaultLight()
    : incomeColor = AppTheme.positive,
      expenseColor = AppTheme.negative,
      successColor = AppTheme.positive,
      warningColor = AppTheme.warningColor;

  const SemanticColors.defaultDark()
    : incomeColor = AppTheme._darkPositive,
      expenseColor = AppTheme._darkNegative,
      successColor = AppTheme._darkPositive,
      warningColor = AppTheme._darkWarning;

  static const SemanticColors light = SemanticColors.defaultLight();

  static const SemanticColors dark = SemanticColors.defaultDark();

  final Color incomeColor;
  final Color expenseColor;
  final Color successColor;
  final Color warningColor;

  @override
  SemanticColors copyWith({
    Color? incomeColor,
    Color? expenseColor,
    Color? successColor,
    Color? warningColor,
  }) {
    return SemanticColors(
      incomeColor: incomeColor ?? this.incomeColor,
      expenseColor: expenseColor ?? this.expenseColor,
      successColor: successColor ?? this.successColor,
      warningColor: warningColor ?? this.warningColor,
    );
  }

  @override
  SemanticColors lerp(
    covariant ThemeExtension<SemanticColors>? other,
    double t,
  ) {
    if (other is! SemanticColors) return this;
    return SemanticColors(
      incomeColor: Color.lerp(incomeColor, other.incomeColor, t)!,
      expenseColor: Color.lerp(expenseColor, other.expenseColor, t)!,
      successColor: Color.lerp(successColor, other.successColor, t)!,
      warningColor: Color.lerp(warningColor, other.warningColor, t)!,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SemanticColors &&
          other.incomeColor == incomeColor &&
          other.expenseColor == expenseColor &&
          other.successColor == successColor &&
          other.warningColor == warningColor;

  @override
  int get hashCode =>
      Object.hash(incomeColor, expenseColor, successColor, warningColor);
}
