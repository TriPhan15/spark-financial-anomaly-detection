import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';

/// WCAG contrast ratio of two opaque colours: 4.5 is the minimum for body text.
double _contrast(Color a, Color b) {
  final la = a.computeLuminance();
  final lb = b.computeLuminance();
  return (math.max(la, lb) + 0.05) / (math.min(la, lb) + 0.05);
}

Color? _textColor(WidgetTester tester, String text) => tester
    .widget<RichText>(
      find.descendant(of: find.text(text), matching: find.byType(RichText)),
    )
    .text
    .style
    ?.color;

Color? _materialColor(WidgetTester tester, Type owner) => tester
    .widget<Material>(
      find
          .descendant(of: find.byType(owner), matching: find.byType(Material))
          .first,
    )
    .color;

List<TextStyle?> _allStyles(TextTheme theme) => [
  theme.displayLarge,
  theme.displayMedium,
  theme.displaySmall,
  theme.headlineLarge,
  theme.headlineMedium,
  theme.headlineSmall,
  theme.titleLarge,
  theme.titleMedium,
  theme.titleSmall,
  theme.bodyLarge,
  theme.bodyMedium,
  theme.bodySmall,
  theme.labelLarge,
  theme.labelMedium,
  theme.labelSmall,
];

void main() {
  // The themes load Poppins through google_fonts; inside testWidgets that load never reaches the network.
  testWidgets(
    'both themes are Material 3 and carry the semantic colours of their brightness',
    (tester) async {
      final light = AppTheme.lightTheme;
      final dark = AppTheme.darkTheme;

      expect(light.useMaterial3, isTrue);
      expect(dark.useMaterial3, isTrue);
      expect(light.brightness, Brightness.light);
      expect(dark.brightness, Brightness.dark);
      expect(light.extension<SemanticColors>(), SemanticColors.light);
      expect(dark.extension<SemanticColors>(), SemanticColors.dark);
    },
  );

  testWidgets('the light theme keeps the brand colours', (tester) async {
    final light = AppTheme.lightTheme;

    expect(light.colorScheme.primary, AppTheme.primary);
    expect(light.scaffoldBackgroundColor, AppTheme.bgLight);
    expect(light.cardTheme.color, Colors.white);
    expect(light.appBarTheme.backgroundColor, AppTheme.bgLight);
  });

  testWidgets(
    'status bar and navigation bar icons are dark on the light theme and light on the dark theme',
    (tester) async {
      final lightBars = AppTheme.lightTheme.appBarTheme.systemOverlayStyle;
      final darkBars = AppTheme.darkTheme.appBarTheme.systemOverlayStyle;

      expect(lightBars, AppTheme.lightOverlayStyle);
      expect(darkBars, AppTheme.darkOverlayStyle);
      expect(lightBars!.statusBarIconBrightness, Brightness.dark);
      expect(lightBars.systemNavigationBarIconBrightness, Brightness.dark);
      expect(darkBars!.statusBarIconBrightness, Brightness.light);
      expect(darkBars.systemNavigationBarIconBrightness, Brightness.light);
      for (final bars in [lightBars, darkBars]) {
        expect(bars.statusBarColor, isNotNull);
        expect(bars.systemNavigationBarColor, isNotNull);
      }
      expect(
        AppTheme.overlayStyleFor(Brightness.light),
        AppTheme.lightOverlayStyle,
      );
      expect(
        AppTheme.overlayStyleFor(Brightness.dark),
        AppTheme.darkOverlayStyle,
      );
    },
  );

  testWidgets(
    'the light colour scheme repeats the legacy colours, so migrated screens look the same in light mode',
    (tester) async {
      final light = AppTheme.lightTheme;
      final scheme = light.colorScheme;

      expect(scheme.primary, AppTheme.mintAccent);
      expect(scheme.onPrimary, AppTheme.navyDark);
      expect(scheme.primaryContainer, AppTheme.mintLight);
      expect(scheme.onPrimaryContainer, AppTheme.primary);
      expect(scheme.surface, AppTheme.navyLight);
      expect(scheme.onSurface, AppTheme.textWhite);
      expect(scheme.onSurfaceVariant, AppTheme.textGray);
      expect(scheme.error, AppTheme.dangerColor);
      expect(scheme.outlineVariant, const Color(0xFFE7E7EC));
      expect(light.scaffoldBackgroundColor, AppTheme.bgLight);
      expect(
        AppTheme.cardDecoration(scheme).color,
        AppTheme.card.color,
        reason: 'the same white card',
      );
      expect(
        AppTheme.cardDecoration(scheme).borderRadius,
        AppTheme.card.borderRadius,
      );
    },
  );

  testWidgets(
    'cards carry no shadow in either theme: their surface colour sets them apart from the page',
    (tester) async {
      for (final theme in [AppTheme.lightTheme, AppTheme.darkTheme]) {
        final scheme = theme.colorScheme;
        final card = AppTheme.cardDecoration(scheme);
        final mode = theme.brightness.name;

        expect(card.boxShadow, isNull, reason: mode);
        expect(card.color, scheme.surface, reason: mode);
        expect(card.borderRadius, BorderRadius.circular(20), reason: mode);
        expect(
          scheme.surface.computeLuminance(),
          greaterThan(theme.scaffoldBackgroundColor.computeLuminance()),
          reason: '$mode: the card is lighter than the page behind it',
        );
      }
    },
  );

  testWidgets(
    'the dark theme has its own palette instead of the light-only legacy colours',
    (tester) async {
      final dark = AppTheme.darkTheme;
      final scheme = dark.colorScheme;
      // Compared role by role: the dark surface may share its value with the old dark *text* colour.
      const lightBackgrounds = [
        AppTheme.navyDark,
        AppTheme.navyLight,
        AppTheme.bgLight,
        AppTheme.mintLight,
      ];
      const lightForegrounds = [
        AppTheme.textWhite,
        AppTheme.textGray,
        AppTheme.mintAccent,
        AppTheme.dangerColor,
        AppTheme.positive,
      ];

      for (final background in [
        dark.scaffoldBackgroundColor,
        scheme.surface,
        scheme.primaryContainer,
      ]) {
        expect(lightBackgrounds, isNot(contains(background)));
      }
      for (final foreground in [
        scheme.onSurface,
        scheme.onSurfaceVariant,
        scheme.primary,
        scheme.outlineVariant,
        scheme.error,
      ]) {
        expect(lightForegrounds, isNot(contains(foreground)));
      }
      expect(AppTheme.cardDecoration(scheme).color, scheme.surface);
      expect(
        AppTheme.cardDecoration(scheme).boxShadow,
        isNull,
        reason: 'a black shadow does not show on a dark background',
      );
    },
  );

  testWidgets(
    'dark text, accents and money colours reach 4.5:1 on every dark background',
    (tester) async {
      final dark = AppTheme.darkTheme;
      final scheme = dark.colorScheme;
      final colors = dark.extension<SemanticColors>()!;
      final backgrounds = {
        'surface': scheme.surface,
        'scaffold': dark.scaffoldBackgroundColor,
        'surfaceContainerHighest': scheme.surfaceContainerHighest,
      };
      final foregrounds = {
        'onSurface': scheme.onSurface,
        'onSurfaceVariant': scheme.onSurfaceVariant,
        'primary': scheme.primary,
        'error': scheme.error,
        'income': colors.incomeColor,
        'expense': colors.expenseColor,
        'success': colors.successColor,
        'warning': colors.warningColor,
      };

      for (final background in backgrounds.entries) {
        for (final foreground in foregrounds.entries) {
          expect(
            _contrast(foreground.value, background.value),
            greaterThanOrEqualTo(4.5),
            reason: '${foreground.key} on ${background.key}',
          );
        }
      }
      expect(
        _contrast(scheme.onSurface, scheme.surface),
        greaterThanOrEqualTo(7),
      );
    },
  );

  testWidgets(
    'every on-colour is readable on its own background in both themes',
    (tester) async {
      for (final theme in [AppTheme.lightTheme, AppTheme.darkTheme]) {
        final scheme = theme.colorScheme;
        final pairs = {
          'onSurface/surface': (scheme.onSurface, scheme.surface),
          'onSurface/scaffold': (
            scheme.onSurface,
            theme.scaffoldBackgroundColor,
          ),
          'primary/surface': (scheme.primary, scheme.surface),
          'onPrimary/primary': (scheme.onPrimary, scheme.primary),
          'onPrimaryContainer/primaryContainer': (
            scheme.onPrimaryContainer,
            scheme.primaryContainer,
          ),
          'onInverseSurface/inverseSurface (snack bars)': (
            scheme.onInverseSurface,
            scheme.inverseSurface,
          ),
        };
        for (final pair in pairs.entries) {
          expect(
            _contrast(pair.value.$1, pair.value.$2),
            greaterThanOrEqualTo(4.5),
            reason: '${theme.brightness.name} ${pair.key}',
          );
        }
      }
      final dark = AppTheme.darkTheme.colorScheme;
      expect(_contrast(dark.onError, dark.error), greaterThanOrEqualTo(4.5));
    },
  );

  testWidgets(
    'dialogs, sheets, dividers, inputs, bars and icons take their colours from the colour scheme',
    (tester) async {
      for (final theme in [AppTheme.lightTheme, AppTheme.darkTheme]) {
        final scheme = theme.colorScheme;
        final mode = theme.brightness.name;

        expect(theme.dialogTheme.backgroundColor, scheme.surface, reason: mode);
        expect(theme.bottomSheetTheme.backgroundColor, scheme.surface);
        expect(theme.bottomSheetTheme.modalBackgroundColor, scheme.surface);
        expect(theme.dividerTheme.color, scheme.outlineVariant, reason: mode);
        expect(theme.bottomAppBarTheme.color, scheme.surface, reason: mode);
        expect(theme.floatingActionButtonTheme.backgroundColor, scheme.primary);
        expect(
          theme.floatingActionButtonTheme.foregroundColor,
          scheme.onPrimary,
        );
        expect(theme.cardTheme.color, scheme.surface, reason: mode);
        expect(theme.iconTheme.color, scheme.onSurface, reason: mode);
        expect(theme.textTheme.bodyMedium?.color, scheme.onSurface);
        expect(theme.textTheme.headlineSmall?.color, scheme.onSurface);
        expect(
          theme.appBarTheme.backgroundColor,
          theme.scaffoldBackgroundColor,
        );
        expect(theme.appBarTheme.iconTheme?.color, scheme.onSurface);
        expect(theme.appBarTheme.titleTextStyle?.color, scheme.onSurface);
        expect(
          theme.bottomNavigationBarTheme.selectedItemColor,
          scheme.primary,
        );
        expect(
          theme.bottomNavigationBarTheme.unselectedItemColor,
          scheme.onSurfaceVariant,
        );
        final inputs = theme.inputDecorationTheme;
        expect(inputs.fillColor, scheme.surface, reason: mode);
        expect(inputs.enabledBorder?.borderSide.color, scheme.outlineVariant);
        expect(inputs.focusedBorder?.borderSide.color, scheme.primary);
        expect(
          AppTheme.overlayStyleFor(theme.brightness).systemNavigationBarColor,
          scheme.surface,
          reason: 'the system navigation bar continues the bottom bar',
        );
        expect(theme.datePickerTheme.backgroundColor, scheme.surface);
        expect(theme.datePickerTheme.headerForegroundColor, scheme.onSurface);
      }
    },
  );

  testWidgets(
    'the light container roles are neutral greys, from the surface down to the old track colour',
    (tester) async {
      final scheme = AppTheme.lightTheme.colorScheme;
      final containers = [
        scheme.surfaceContainerLowest,
        scheme.surfaceContainerLow,
        scheme.surfaceContainer,
        scheme.surfaceContainerHigh,
        scheme.surfaceContainerHighest,
      ];
      final oldTrack = Color.alphaBlend(
        AppTheme.textGray.withValues(alpha: 0.2),
        Colors.white,
      );

      expect(scheme.surfaceContainerLowest, scheme.surface);
      expect(scheme.surfaceContainer, AppTheme.bgLight);
      expect(scheme.surfaceContainerHighest.toARGB32(), oldTrack.toARGB32());
      for (final color in containers) {
        final argb = color.toARGB32();
        final channels = [(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF];
        expect(
          channels.reduce(math.max) - channels.reduce(math.min),
          lessThanOrEqualTo(2),
          reason: 'no tint in $color',
        );
      }
      for (var i = 1; i < containers.length; i++) {
        expect(
          containers[i].computeLuminance(),
          lessThan(containers[i - 1].computeLuminance()),
        );
      }
    },
  );

  testWidgets('the soft shadow is kept for light surfaces only', (
    tester,
  ) async {
    expect(
      AppTheme.softShadowFor(AppTheme.lightTheme.colorScheme),
      AppTheme.softShadow,
    );
    expect(AppTheme.softShadowFor(AppTheme.darkTheme.colorScheme), isNull);
  });

  group('rendered with AppTheme', () {
    late BuildContext bodyContext;

    Future<ThemeData> pumpApp(WidgetTester tester, ThemeMode mode) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          darkTheme: AppTheme.darkTheme,
          themeMode: mode,
          home: Scaffold(
            appBar: AppBar(title: const Text('Tiêu đề')),
            body: Builder(
              builder: (context) {
                bodyContext = context;
                return const Column(
                  children: [
                    Text('Nội dung'),
                    Divider(),
                    TextField(decoration: InputDecoration(hintText: 'Gợi ý')),
                  ],
                );
              },
            ),
          ),
        ),
      );
      return Theme.of(bodyContext);
    }

    for (final mode in [ThemeMode.light, ThemeMode.dark]) {
      testWidgets(
        '${mode.name}: the page, its text, a divider, a hint and the system bars follow the theme',
        (tester) async {
          final theme = await pumpApp(tester, mode);
          final scheme = theme.colorScheme;
          final divider =
              tester
                      .widget<Container>(
                        find.descendant(
                          of: find.byType(Divider),
                          matching: find.byType(Container),
                        ),
                      )
                      .decoration!
                  as BoxDecoration;

          expect(
            theme.brightness,
            mode == ThemeMode.dark ? Brightness.dark : Brightness.light,
          );
          expect(
            _materialColor(tester, Scaffold),
            theme.scaffoldBackgroundColor,
          );
          expect(_textColor(tester, 'Tiêu đề'), scheme.onSurface);
          expect(_textColor(tester, 'Nội dung'), scheme.onSurface);
          expect(_textColor(tester, 'Gợi ý'), scheme.onSurfaceVariant);
          expect(
            (divider.border! as Border).bottom.color,
            scheme.outlineVariant,
          );
          expect(
            SystemChrome.latestStyle,
            AppTheme.overlayStyleFor(theme.brightness),
            reason: 'the app bar applies the system bars of its theme',
          );
        },
      );

      testWidgets(
        '${mode.name}: dialogs, bottom sheets and snack bars are readable',
        (tester) async {
          final scheme = (await pumpApp(tester, mode)).colorScheme;

          showDialog<void>(
            context: bodyContext,
            builder: (_) => const AlertDialog(
              title: Text('Hộp thoại'),
              content: Text('Nội dung hộp thoại'),
            ),
          );
          await tester.pumpAndSettle();
          expect(_materialColor(tester, AlertDialog), scheme.surface);
          expect(_textColor(tester, 'Hộp thoại'), scheme.onSurface);
          expect(_textColor(tester, 'Nội dung hộp thoại'), scheme.onSurface);
          Navigator.of(bodyContext).pop();
          await tester.pumpAndSettle();

          showModalBottomSheet<void>(
            context: bodyContext,
            builder: (_) => const Text('Trang dưới'),
          );
          await tester.pumpAndSettle();
          expect(_materialColor(tester, BottomSheet), scheme.surface);
          expect(_textColor(tester, 'Trang dưới'), scheme.onSurface);
          Navigator.of(bodyContext).pop();
          await tester.pumpAndSettle();

          ScaffoldMessenger.of(
            bodyContext,
          ).showSnackBar(const SnackBar(content: Text('Thông báo')));
          await tester.pumpAndSettle();
          expect(_materialColor(tester, SnackBar), scheme.inverseSurface);
          expect(_textColor(tester, 'Thông báo'), scheme.onInverseSurface);
        },
      );
    }
  });

  testWidgets('every text style uses tabular figures so that amounts line up', (
    tester,
  ) async {
    for (final theme in [AppTheme.lightTheme, AppTheme.darkTheme]) {
      for (final style in [
        ..._allStyles(theme.textTheme),
        ..._allStyles(theme.primaryTextTheme),
      ]) {
        expect(
          style?.fontFeatures,
          contains(const FontFeature.tabularFigures()),
        );
      }
    }
  });

  group('SemanticColors', () {
    const green = Color(0xFF00FF00);
    const red = Color(0xFFFF0000);
    const colors = SemanticColors(incomeColor: green, expenseColor: red);

    test('copyWith replaces only the colours it is given', () {
      expect(
        colors.copyWith(expenseColor: green),
        const SemanticColors(incomeColor: green, expenseColor: green),
      );
      expect(colors.copyWith(), colors);
    });

    test('lerp blends both colours, and keeps its own without a target', () {
      const black = SemanticColors(
        incomeColor: Color(0xFF000000),
        expenseColor: Color(0xFF000000),
      );

      final half = colors.lerp(black, 0.5);

      expect(half.incomeColor, Color.lerp(green, const Color(0xFF000000), 0.5));
      expect(half.expenseColor, Color.lerp(red, const Color(0xFF000000), 0.5));
      expect(colors.lerp(null, 0.5), same(colors));
    });

    test('two instances with the same colours are equal', () {
      expect(
        const SemanticColors(incomeColor: green, expenseColor: red),
        colors,
      );
      expect(
        const SemanticColors(incomeColor: green, expenseColor: red).hashCode,
        colors.hashCode,
      );
    });

    test(
      'defaultLight keeps the colours the screens used, and both defaults are the palettes the themes register',
      () {
        const light = SemanticColors.defaultLight();

        expect(light.incomeColor, AppTheme.positive);
        expect(light.expenseColor, AppTheme.negative);
        expect(light.successColor, AppTheme.positive);
        expect(light.warningColor, AppTheme.warningColor);
        expect(light, SemanticColors.light);
        expect(const SemanticColors.defaultDark(), SemanticColors.dark);
        expect(SemanticColors.dark, isNot(SemanticColors.light));
      },
    );

    test(
      'a two-colour instance gets the light success and warning colours',
      () {
        expect(colors.successColor, AppTheme.positive);
        expect(colors.warningColor, AppTheme.warningColor);
      },
    );

    test(
      'copyWith, lerp and equality cover the success and warning colours',
      () {
        const blue = Color(0xFF0000FF);
        const full = SemanticColors(
          incomeColor: green,
          expenseColor: red,
          successColor: green,
          warningColor: red,
        );

        expect(
          full.copyWith(successColor: blue, warningColor: blue),
          const SemanticColors(
            incomeColor: green,
            expenseColor: red,
            successColor: blue,
            warningColor: blue,
          ),
        );
        final half = full.lerp(
          full.copyWith(successColor: blue, warningColor: blue),
          0.5,
        );
        expect(half.successColor, Color.lerp(green, blue, 0.5));
        expect(half.warningColor, Color.lerp(red, blue, 0.5));
        expect(full.copyWith(successColor: blue), isNot(full));
        expect(full.copyWith(warningColor: blue), isNot(full));
      },
    );
  });
}
