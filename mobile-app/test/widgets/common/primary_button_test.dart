import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/widgets/common/primary_button.dart';

import '../../helpers/theme_colors.dart';

/// Pumps an enabled PrimaryButton under the app themes in [mode] and returns the theme it was built with.
Future<ThemeData> _pump(WidgetTester tester, ThemeMode mode) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: mode,
      home: Scaffold(
        body: PrimaryButton(text: 'Đăng nhập', onPressed: () {}),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return Theme.of(tester.element(find.byType(PrimaryButton)));
}

void main() {
  testWidgets('light mode keeps the colours it had before the themes', (
    tester,
  ) async {
    await _pump(tester, ThemeMode.light);

    expect(
      materialColor(tester, find.byType(ElevatedButton)),
      AppTheme.mintAccent,
    );
    expect(paintedColor(tester, find.text('Đăng nhập')), AppTheme.navyDark);
  });

  testWidgets(
    'dark mode takes primary with the onPrimary label, readable at 4.5:1',
    (tester) async {
      final colors = (await _pump(tester, ThemeMode.dark)).colorScheme;

      final background = materialColor(tester, find.byType(ElevatedButton));
      final label = paintedColor(tester, find.text('Đăng nhập'));
      expect(background, colors.primary);
      expect(label, colors.onPrimary);
      expect(contrastRatio(label!, background!), greaterThanOrEqualTo(4.5));
    },
  );

  testWidgets('keeps its full width, its height and its rounder corners', (
    tester,
  ) async {
    await _pump(tester, ThemeMode.dark);

    final size = tester.getSize(find.byType(ElevatedButton));
    expect(size.height, 56);
    expect(size.width, tester.getSize(find.byType(Scaffold)).width);
    final shape =
        tester
                .widget<ElevatedButton>(find.byType(ElevatedButton))
                .style!
                .shape!
                .resolve({})
            as RoundedRectangleBorder;
    expect(shape.borderRadius, BorderRadius.circular(16));
  });
}
