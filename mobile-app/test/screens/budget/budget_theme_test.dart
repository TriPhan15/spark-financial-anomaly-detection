import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shimmer/shimmer.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/constants/transaction_categories.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/core/utils/currency_helper.dart';
import 'package:finance_ai_app/screens/budget/budget_screen.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../../helpers/app_settings_scope.dart';
import '../../helpers/fake_backend.dart';
import '../../helpers/fake_settings_repository.dart';
import '../../helpers/theme_colors.dart';

Map<String, Object?> _budget() => {
  'id': 7,
  'categoryId': 10,
  'categoryName': 'Ăn uống',
  'amount': 2000000,
  'periodType': 'MONTH',
  'startDate': '2026-09-01',
  'endDate': '2026-09-30',
};

const List<Map<String, Object?>> _serverCategories = [
  {'id': 10, 'name': 'Ăn uống', 'type': 'EXPENSE'},
  {'id': 11, 'name': 'Di chuyển', 'type': 'EXPENSE'},
];

void main() {
  final backend = FakeBackend();

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
  });

  setUp(() async {
    // SharedPreferences shares one Future that must be created inside the test body (see budget_display_test.dart).
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    SharedPreferences.setMockInitialValues({});

    backend.reset();
    backend.on('GET', '/api/budgets', (_) => okResponse([_budget()]));
    backend.on('GET', '/api/categories', (_) => okResponse(_serverCategories));
  });

  // The language cubit of withAppSettings sets the default locale of intl.
  tearDown(() => Intl.defaultLocale = null);

  Future<void> start(WidgetTester tester, ThemeMode mode) async {
    tester.view.physicalSize = const Size(600, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      withAppSettings(
        const BudgetScreen(),
        settings: FakeSettingsRepository(themeMode: mode),
      ),
    );
  }

  ColorScheme colorsOf(WidgetTester tester) =>
      Theme.of(tester.element(find.byType(BudgetScreen))).colorScheme;

  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    group('${mode.name} theme', () {
      testWidgets(
        'the loading skeleton shimmers from the container colour to the surface',
        (tester) async {
          final answer = Completer<ResponseBody>();
          backend.on('GET', '/api/budgets', (_) => answer.future);

          await start(tester, mode);
          // The shimmer animates while the request is pending: frames only, no settling.
          await tester.pump(const Duration(milliseconds: 100));
          final colors = colorsOf(tester);
          final shimmer = tester.widget<Shimmer>(find.byType(Shimmer));

          expect(shimmer.gradient.colors, [
            colors.surfaceContainerHighest,
            colors.surfaceContainerHighest,
            colors.surface,
            colors.surfaceContainerHighest,
            colors.surfaceContainerHighest,
          ]);
          expect(
            contrastRatio(colors.surfaceContainerHighest, colors.surface),
            greaterThan(1.1),
            reason: 'the shimmer must show on the page',
          );

          answer.complete(okResponse([_budget()]));
          await tester.pumpAndSettle();
          expect(find.byType(Shimmer), findsNothing);
        },
      );

      testWidgets('the page and a budget card follow the theme', (
        tester,
      ) async {
        await start(tester, mode);
        await tester.pumpAndSettle();
        final colors = colorsOf(tester);
        final categoryIcon = find.byIcon(
          TransactionCategories.iconFor('Ăn uống'),
        );

        expect(materialColor(tester, find.byType(Scaffold)), colors.surface);
        expect(paintedColor(tester, find.text('Ngân sách')), colors.onSurface);
        expect(
          paintedColor(tester, find.byIcon(Icons.add_rounded)),
          colors.primary,
        );
        expect(
          boxDecoration(tester, find.text('Ăn uống')),
          AppTheme.cardDecoration(colors),
        );
        expect(boxColor(tester, categoryIcon), colors.primaryContainer);
        expect(paintedColor(tester, categoryIcon), colors.onPrimaryContainer);
        expect(paintedColor(tester, find.text('Ăn uống')), colors.onSurface);
        expect(
          paintedColor(tester, find.textContaining('Tháng ·')),
          colors.onSurfaceVariant,
        );
        expect(
          paintedColor(
            tester,
            find.text(CurrencyHelper.formatVND(2000000, showSign: false)),
          ),
          colors.primary,
        );
        expect(
          paintedColor(tester, find.byIcon(Icons.delete_outline_rounded)),
          colors.error,
        );
      });

      testWidgets('the empty state follows the theme', (tester) async {
        backend.on('GET', '/api/budgets', (_) => okResponse([]));
        await start(tester, mode);
        await tester.pumpAndSettle();
        final colors = colorsOf(tester);

        expect(
          boxColor(tester, find.text('Chưa có ngân sách nào.')),
          colors.surface,
        );
        expect(
          paintedColor(tester, find.text('Chưa có ngân sách nào.')),
          colors.onSurfaceVariant,
        );
        expect(
          paintedColor(tester, find.byIcon(Icons.pie_chart_outline_rounded)),
          colors.onSurfaceVariant,
        );
      });

      testWidgets(
        'the form sheet: background, chips, labels and the amount field follow the theme',
        (tester) async {
          await start(tester, mode);
          await tester.pumpAndSettle();
          final colors = colorsOf(tester);

          await tester.tap(find.byIcon(Icons.add_rounded));
          await tester.pumpAndSettle();

          expect(
            materialColor(tester, find.byType(BottomSheet)),
            colors.surface,
          );
          expect(
            paintedColor(tester, find.text('Thiết lập ngân sách')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('Kỳ hạn')),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.event_rounded)),
            colors.primary,
          );

          // Period chips: the chosen one is filled with the primary colour, the other one sits on the surface.
          ChoiceChip chip(String label) =>
              tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, label));
          expect(chip('Tháng').selectedColor, colors.primary);
          expect(paintedColor(tester, find.text('Tháng')), colors.onPrimary);
          expect(chip('Tuần').backgroundColor, colors.surface);
          expect(chip('Tuần').side?.color, colors.outlineVariant);
          expect(paintedColor(tester, find.text('Tuần')), colors.onSurface);

          // Amount field: fill, borders, label, text and cursor from the theme.
          final field = find.byType(TextField);
          final decoration = tester
              .widget<InputDecorator>(
                find.descendant(
                  of: field,
                  matching: find.byType(InputDecorator),
                ),
              )
              .decoration;
          expect(decoration.fillColor, colors.surface);
          expect(
            (decoration.enabledBorder! as OutlineInputBorder).borderSide.color,
            colors.outlineVariant,
          );
          expect(
            (decoration.focusedBorder! as OutlineInputBorder).borderSide.color,
            colors.primary,
          );
          expect(
            paintedColor(tester, find.text('Số tiền ngân sách (₫)')),
            colors.onSurfaceVariant,
          );
          final editable = tester.widget<EditableText>(
            find.byType(EditableText),
          );
          expect(editable.style.color, colors.onSurface);
          expect(editable.cursorColor, colors.primary);
          expect(
            contrastRatio(editable.cursorColor, colors.surface),
            greaterThanOrEqualTo(3),
            reason: 'the cursor must show on the field',
          );
        },
      );
    });
  }

  testWidgets(
    'light mode keeps the colours the budget card had before the migration',
    (tester) async {
      await start(tester, ThemeMode.light);
      await tester.pumpAndSettle();

      // Same white card; since batch 5 cards have no shadow (see AppTheme.cardDecoration).
      final card = boxDecoration(tester, find.text('Ăn uống'));
      expect(card.color, AppTheme.card.color);
      expect(card.borderRadius, AppTheme.card.borderRadius);
      expect(card.boxShadow, isNull);
      expect(paintedColor(tester, find.text('Ăn uống')), AppTheme.textWhite);
      expect(
        paintedColor(tester, find.textContaining('Tháng ·')),
        AppTheme.textGray,
      );
      expect(
        paintedColor(
          tester,
          find.text(CurrencyHelper.formatVND(2000000, showSign: false)),
        ),
        AppTheme.mintAccent,
      );
      expect(
        paintedColor(tester, find.byIcon(Icons.delete_outline_rounded)),
        AppTheme.dangerColor,
      );
    },
  );
}
