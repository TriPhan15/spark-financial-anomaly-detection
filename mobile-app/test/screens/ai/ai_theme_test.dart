import 'dart:async';

import 'package:dio/dio.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/ai/ai_tab_page.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../../helpers/app_settings_scope.dart';
import '../../helpers/fake_backend.dart';
import '../../helpers/fake_local_store.dart';
import '../../helpers/fake_settings_repository.dart';
import '../../helpers/theme_colors.dart';

/// Today at noon, so that every transaction falls in this week and this month.
final DateTime _today = DateTime(
  DateTime.now().year,
  DateTime.now().month,
  DateTime.now().day,
  12,
);

Map<String, Object?> _tx(
  int id,
  num amount,
  String category, {
  bool isExpense = true,
}) => {
  'id': id,
  'title': category,
  'amount': amount,
  'date': _today.toIso8601String(),
  'category': category,
  'isExpense': isExpense,
};

/// Income 10M; needs 2M (20%), wants 4M (40%, past the 30% target), savings 40%.
final List<Map<String, Object?>> _transactions = [
  _tx(1, 10000000, 'Lương', isExpense: false),
  _tx(2, 2000000, 'Ăn uống'),
  _tx(3, 4000000, 'Giải trí'),
];

ResponseBody _patterns(List<Map<String, Object?>> patterns) =>
    jsonResponse(200, {'patterns': patterns});

Finder _chipLabel(String label) => find.descendant(
  of: find.widgetWithText(ChoiceChip, label),
  matching: find.text(label),
);

void main() {
  final backend = FakeBackend();

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
    // The AI tab also reads the transactions waiting to be sent; the fake keeps it away from a real database.
    AppRepository.instance.localStore = FakeLocalStore();
  });

  setUp(() async {
    // SharedPreferences shares one Future that must be created inside the test body (see budget_display_test.dart).
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    SharedPreferences.setMockInitialValues({});

    backend.reset();
    backend.on(
      'GET',
      '/api/transactions/mine',
      (_) => okResponse(_transactions),
    );
    backend.on('GET', '/api/savings', (_) => okResponse([]));
    backend.on(
      'POST',
      '/spade/analyze',
      (_) => _patterns([
        {'pattern': 'an uong → giai tri', 'count': 3, 'wsupport': '12.5%'},
      ]),
    );
  });

  // The language cubit of withAppSettings sets the default locale of intl.
  tearDown(() => Intl.defaultLocale = null);

  Future<void> start(WidgetTester tester, ThemeMode mode) async {
    // Tall, so that every card is built without scrolling, and wide, because the test font is much wider than a real one.
    tester.view.physicalSize = const Size(700, 3200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      withAppSettings(
        const AITabPage(),
        settings: FakeSettingsRepository(themeMode: mode),
      ),
    );
  }

  ColorScheme colorsOf(WidgetTester tester) =>
      Theme.of(tester.element(find.byType(AITabPage))).colorScheme;

  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    group('${mode.name} theme', () {
      testWidgets(
        'insight cards and the safe-to-spend card follow the theme; the card icons keep their colours',
        (tester) async {
          await start(tester, mode);
          await tester.pumpAndSettle();
          final colors = colorsOf(tester);

          expect(boxColor(tester, find.text('Chi nhiều nhất')), colors.surface);
          expect(
            paintedColor(tester, find.text('Chi nhiều nhất')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.textContaining('tổng chi')),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.category_rounded)),
            Colors.orange,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.trending_up_rounded).first),
            colors.primary,
          );

          final safe = boxDecoration(
            tester,
            find.text('Hôm nay tiêu được bao nhiêu?'),
          );
          expect(safe.color, colors.surface);
          expect(
            (safe.border! as Border).top.color,
            colors.primary.withValues(alpha: 0.35),
          );
          expect(
            paintedColor(tester, find.textContaining('/ ngày')),
            colors.primary,
          );
        },
      );

      testWidgets(
        'the chart: bars, their tracks, grid, labels and the touch tooltip follow the theme',
        (tester) async {
          await start(tester, mode);
          await tester.pumpAndSettle();
          final colors = colorsOf(tester);
          final chart = tester.widget<BarChart>(find.byType(BarChart)).data;
          final group = chart.barGroups.first;
          final rod = group.barRods.first;

          expect(boxColor(tester, find.byType(BarChart)), colors.surface);
          expect(rod.color, colors.primary);
          expect(rod.backDrawRodData.color, colors.surfaceContainerHighest);
          expect(
            chart.gridData.getDrawingHorizontalLine(0).color,
            colors.onSurfaceVariant.withValues(alpha: 0.1),
          );
          expect(
            paintedColor(
              tester,
              find
                  .descendant(
                    of: find.byType(BarChart),
                    matching: find.byType(Text),
                  )
                  .first,
            ),
            colors.onSurfaceVariant,
          );

          final tooltip = chart.barTouchData.touchTooltipData;
          final item = tooltip.getTooltipItem(group, 0, rod, 0)!;
          expect(tooltip.getTooltipColor(group), colors.surfaceContainer);
          expect(item.textStyle.color, colors.onSurface);
          expect(
            contrastRatio(item.textStyle.color!, colors.surfaceContainer),
            greaterThanOrEqualTo(4.5),
            reason: 'no dark text on a dark tooltip',
          );
        },
      );

      testWidgets(
        'the period filter marks the chosen period and leaves the others neutral',
        (tester) async {
          await start(tester, mode);
          await tester.pumpAndSettle();
          final colors = colorsOf(tester);
          ChoiceChip chip(String label) =>
              tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, label));

          expect(chip('Tháng này').selectedColor, colors.primaryContainer);
          expect(chip('Tháng này').side?.color, colors.primary);
          expect(
            paintedColor(tester, _chipLabel('Tháng này')),
            colors.onPrimaryContainer,
          );
          expect(chip('Tuần này').backgroundColor, colors.surface);
          expect(chip('Tuần này').side?.color, colors.outlineVariant);
          expect(
            paintedColor(tester, _chipLabel('Tuần này')),
            colors.onSurfaceVariant,
          );
          expect(paintedColor(tester, find.text('Làm mới')), colors.primary);
        },
      );

      testWidgets(
        'the SPADE patterns and the 50/30/20 card follow the theme; a share past its target turns to the error colour',
        (tester) async {
          await start(tester, mode);
          await tester.pumpAndSettle();
          final colors = colorsOf(tester);
          const pattern = 'Ăn uống → Giải trí';

          final card = boxDecoration(tester, find.text(pattern));
          expect(card.color, colors.surface);
          expect(
            (card.border! as Border).top.color,
            colors.primary.withValues(alpha: 0.2),
          );
          expect(paintedColor(tester, find.text(pattern)), colors.onSurface);
          expect(boxColor(tester, find.text('#1')), colors.primaryContainer);
          expect(
            paintedColor(tester, find.text('#1')),
            colors.onPrimaryContainer,
          );
          expect(
            paintedColor(tester, find.textContaining('Tần suất')),
            colors.primary,
          );
          expect(
            paintedColor(tester, find.text('WCM-SPADE')),
            colors.onPrimaryContainer,
          );

          expect(paintedColor(tester, find.text('40% / ≤30%')), colors.error);
          expect(
            paintedColor(tester, find.text('20% / ≤50%')),
            Colors.blueAccent,
          );
          expect(paintedColor(tester, find.text('40% / ≥20%')), colors.primary);
          final bars = tester.widgetList<LinearProgressIndicator>(
            find.byType(LinearProgressIndicator),
          );
          expect(bars, hasLength(3));
          for (final bar in bars) {
            expect(bar.backgroundColor, colors.surfaceContainerHighest);
          }
          expect(bars.map((bar) => bar.valueColor?.value), [
            Colors.blueAccent,
            colors.error,
            colors.primary,
          ]);
        },
      );

      testWidgets(
        'the loading, analysis-error and empty states follow the theme',
        (tester) async {
          final transactions = Completer<ResponseBody>();
          backend.on(
            'GET',
            '/api/transactions/mine',
            (_) => transactions.future,
          );
          backend.on(
            'POST',
            '/spade/analyze',
            (_) => jsonResponse(500, {'detail': 'down'}),
          );

          await start(tester, mode);
          await tester.pump(const Duration(milliseconds: 100));
          final colors = colorsOf(tester);
          expect(
            tester
                .widget<CircularProgressIndicator>(
                  find.byType(CircularProgressIndicator),
                )
                .color,
            isNull,
            reason: 'the spinner takes the primary colour of the theme',
          );

          transactions.complete(okResponse(_transactions));
          await tester.pumpAndSettle();
          const failed = 'Không tải được phân tích';
          expect(boxColor(tester, find.text(failed)), colors.surface);
          expect(paintedColor(tester, find.text(failed)), colors.onSurface);
          expect(
            paintedColor(
              tester,
              find.text('Kiểm tra kết nối rồi làm mới để thử lại.'),
            ),
            colors.onSurfaceVariant,
          );
          expect(
            boxColor(tester, find.byIcon(Icons.cloud_off_rounded)),
            colors.primaryContainer,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.cloud_off_rounded)),
            colors.onPrimaryContainer,
          );

          backend.on('GET', '/api/transactions/mine', (_) => okResponse([]));
          await tester.pumpWidget(const SizedBox());
          await start(tester, mode);
          await tester.pumpAndSettle();
          expect(
            boxColor(tester, find.text('Chưa có dữ liệu')),
            colors.surface,
          );
          expect(
            paintedColor(tester, find.text('Chưa có dữ liệu')),
            colors.onSurface,
          );
        },
      );

      testWidgets(
        'the allocation advice: a cut in the error colour, a keep in the primary colour, tips in amber',
        (tester) async {
          backend.on(
            'GET',
            '/api/savings',
            (_) => okResponse([
              {
                'id': 1,
                'title': 'Du lịch',
                'target': 10000000,
                'current': 1000000,
                'deadline': '31/12/${_today.year + 1}',
              },
            ]),
          );
          backend.on(
            'POST',
            '/spade/recommend',
            (_) => jsonResponse(200, {
              'summary': 'Giảm chi giải trí để kịp mục tiêu.',
              'recommendations': [
                {
                  'action': 'Cắt',
                  'tag': 'Giải trí',
                  'delta': -500000,
                  'current': 4000000,
                  'suggested': 3500000,
                  'reason': 'Vượt chuẩn 30%',
                },
                {
                  'action': 'Giữ',
                  'tag': 'Ăn uống',
                  'delta': 200000,
                  'current': 2000000,
                  'suggested': 2200000,
                  'reason': '',
                },
              ],
            }),
          );

          await start(tester, mode);
          await tester.pumpAndSettle();
          final colors = colorsOf(tester);

          expect(
            paintedColor(tester, find.text('Cắt · Giải trí')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.trending_down_rounded)),
            colors.error,
          );
          expect(
            (boxDecoration(tester, find.text('Cắt · Giải trí')).border!
                    as Border)
                .top
                .color,
            colors.error.withValues(alpha: 0.25),
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.trending_up_rounded).last),
            colors.primary,
          );
          expect(
            paintedColor(tester, find.text('Vượt chuẩn 30%')),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(
              tester,
              find.text('Giảm chi giải trí để kịp mục tiêu.'),
            ),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.tips_and_updates_rounded)),
            Colors.amber,
          );
          if (mode == ThemeMode.dark) {
            for (final accent in [colors.error, colors.primary, Colors.amber]) {
              expect(
                contrastRatio(accent, colors.surface),
                greaterThanOrEqualTo(4.5),
              );
            }
          }
        },
      );
    });
  }

  testWidgets(
    'light mode keeps the colours the AI tab had before the migration',
    (tester) async {
      await start(tester, ThemeMode.light);
      await tester.pumpAndSettle();

      expect(boxColor(tester, find.text('Chi nhiều nhất')), AppTheme.navyLight);
      expect(
        paintedColor(tester, find.text('Chi nhiều nhất')),
        AppTheme.textWhite,
      );
      expect(
        paintedColor(tester, find.textContaining('tổng chi')),
        AppTheme.textGray,
      );
      final chart = tester.widget<BarChart>(find.byType(BarChart)).data;
      expect(chart.barGroups.first.barRods.first.color, AppTheme.mintAccent);
      expect(
        tester
            .widget<ChoiceChip>(find.widgetWithText(ChoiceChip, 'Tháng này'))
            .selectedColor,
        AppTheme.mintLight,
      );
      expect(paintedColor(tester, _chipLabel('Tháng này')), AppTheme.primary);
      expect(paintedColor(tester, find.text('Tuần này')), AppTheme.textGray);
    },
  );
}
