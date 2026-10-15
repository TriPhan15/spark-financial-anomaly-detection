import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/models/budget_model.dart';
import 'package:finance_ai_app/models/budget_progress.dart';
import 'package:finance_ai_app/widgets/budget/budget_progress_card.dart';
import 'package:finance_ai_app/widgets/budget/budget_section.dart';

final NumberFormat _money = NumberFormat.currency(
  locale: 'vi_VN',
  symbol: 'đ',
  decimalDigits: 0,
);

BudgetProgress _progress(
  double spent, {
  int id = 1,
  String? categoryName = 'Ăn uống',
  double limit = 1000000,
  String periodType = BudgetPeriodType.month,
  String start = '2026-09-01',
  String end = '2026-09-30',
}) => BudgetProgress(
  budget: BudgetModel(
    id: id,
    categoryId: categoryName == null ? null : 10,
    categoryName: categoryName,
    amount: limit,
    periodType: periodType,
    startDate: start,
    endDate: end,
  ),
  spent: spent,
);

/// The card takes its colours from the app theme; in light mode they are the colours it had before the themes.
Future<void> _pump(
  WidgetTester tester,
  Widget child, {
  ThemeMode mode = ThemeMode.light,
}) => tester.pumpWidget(
  MaterialApp(
    theme: AppTheme.lightTheme,
    darkTheme: AppTheme.darkTheme,
    themeMode: mode,
    home: Scaffold(body: SingleChildScrollView(child: child)),
  ),
);

double _contrast(Color a, Color b) {
  final la = a.computeLuminance();
  final lb = b.computeLuminance();
  return ((la > lb ? la : lb) + 0.05) / ((la > lb ? lb : la) + 0.05);
}

LinearProgressIndicator _bar(WidgetTester tester) => tester
    .widget<LinearProgressIndicator>(find.byType(LinearProgressIndicator));

Color _barColor(WidgetTester tester) => _bar(tester).valueColor!.value!;

void main() {
  group('BudgetProgressCard', () {
    testWidgets(
      'a budget in use: amounts, period, what is left, percent and a normal bar',
      (tester) async {
        await _pump(tester, BudgetProgressCard(progress: _progress(500000)));

        expect(find.text('Ăn uống'), findsOneWidget);
        expect(find.text('Tháng · 01/09/2026 – 30/09/2026'), findsOneWidget);
        expect(
          find.text('${_money.format(500000)} / ${_money.format(1000000)}'),
          findsOneWidget,
        );
        expect(find.text('Còn lại: ${_money.format(500000)}'), findsOneWidget);
        expect(find.text('50%'), findsOneWidget);
        expect(_bar(tester).value, 0.5);
        expect(_barColor(tester), AppTheme.primary);
      },
    );

    testWidgets(
      'from 80% the bar turns orange and the card says it is close to the limit',
      (tester) async {
        await _pump(tester, BudgetProgressCard(progress: _progress(850000)));

        expect(
          find.text('Sắp chạm hạn mức · Còn lại: ${_money.format(150000)}'),
          findsOneWidget,
        );
        expect(find.text('85%'), findsOneWidget);
        expect(_barColor(tester), AppTheme.warningColor);
      },
    );

    testWidgets('exactly 80% is already a warning, 79% is not', (tester) async {
      await _pump(tester, BudgetProgressCard(progress: _progress(800000)));
      expect(_barColor(tester), AppTheme.warningColor);

      await _pump(tester, BudgetProgressCard(progress: _progress(799999)));
      expect(_barColor(tester), AppTheme.primary);
      expect(find.text('79%'), findsOneWidget);
    });

    testWidgets(
      'from 100% the bar is red and full, and the card says by how much the limit was passed',
      (tester) async {
        await _pump(tester, BudgetProgressCard(progress: _progress(1150000)));

        expect(
          find.text('Đã vượt hạn mức: ${_money.format(150000)}'),
          findsOneWidget,
        );
        expect(find.text('115%'), findsOneWidget);
        expect(_bar(tester).value, 1.0);
        expect(_barColor(tester), AppTheme.dangerColor);
      },
    );

    testWidgets(
      'a budget without a category is called "Tất cả danh mục"; a weekly one says "Tuần"',
      (tester) async {
        await _pump(
          tester,
          BudgetProgressCard(
            progress: _progress(
              10,
              categoryName: null,
              periodType: BudgetPeriodType.week,
              start: '2026-09-14',
              end: '2026-09-20',
            ),
          ),
        );

        expect(find.text('Tất cả danh mục'), findsOneWidget);
        expect(find.text('Tuần · 14/09/2026 – 20/09/2026'), findsOneWidget);
      },
    );
  });

  group('BudgetProgressCard in both themes', () {
    testWidgets(
      'the track is the neutral container colour: the old grey in light, a raised tone in dark',
      (tester) async {
        await _pump(tester, BudgetProgressCard(progress: _progress(500000)));
        expect(_bar(tester).backgroundColor, const Color(0xFFEBEBEC));

        await _pump(
          tester,
          BudgetProgressCard(progress: _progress(500000)),
          mode: ThemeMode.dark,
        );
        await tester.pumpAndSettle();
        expect(
          _bar(tester).backgroundColor,
          AppTheme.darkTheme.colorScheme.surfaceContainerHighest,
        );
      },
    );

    testWidgets(
      'in dark mode the bar, the status and the card take the dark palette at every level',
      (tester) async {
        final colors = AppTheme.darkTheme.colorScheme;
        final semantic = AppTheme.darkTheme.extension<SemanticColors>()!;
        for (final (spent, status, bar, statusColor) in [
          (
            500000.0,
            'Còn lại: ${_money.format(500000)}',
            colors.primary,
            semantic.successColor,
          ),
          (
            850000.0,
            'Sắp chạm hạn mức · Còn lại: ${_money.format(150000)}',
            semantic.warningColor,
            semantic.warningColor,
          ),
          (
            1150000.0,
            'Đã vượt hạn mức: ${_money.format(150000)}',
            colors.error,
            colors.error,
          ),
        ]) {
          await _pump(
            tester,
            BudgetProgressCard(progress: _progress(spent)),
            mode: ThemeMode.dark,
          );
          await tester.pumpAndSettle();

          expect(_barColor(tester), bar, reason: status);
          expect(
            tester.widget<Text>(find.text(status)).style?.color,
            statusColor,
          );
          final card =
              tester
                      .widget<Container>(
                        find
                            .ancestor(
                              of: find.text('Ăn uống'),
                              matching: find.byType(Container),
                            )
                            .first,
                      )
                      .decoration!
                  as BoxDecoration;
          expect(card, AppTheme.cardDecoration(colors));
          // Readable on the card: text colours at 4.5:1, the bar against its track at 3:1.
          final track = colors.surfaceContainerHighest;
          expect(_contrast(statusColor, colors.surface), greaterThan(4.5));
          expect(_contrast(bar, track), greaterThan(3));
        }
      },
    );
  });

  group('BudgetSection', () {
    testWidgets('lists one card per budget under a title', (tester) async {
      await _pump(
        tester,
        BudgetSection(
          progresses: [
            _progress(100, id: 1),
            _progress(200, id: 2, categoryName: 'Di chuyển'),
          ],
        ),
      );

      expect(find.text('Ngân sách'), findsOneWidget);
      expect(find.byType(BudgetProgressCard), findsNWidgets(2));
    });

    testWidgets('says so when there is nothing to show', (tester) async {
      await _pump(
        tester,
        const BudgetSection(
          progresses: [],
          emptyText: 'Chưa có ngân sách trong kỳ này.',
        ),
      );

      expect(find.text('Chưa có ngân sách trong kỳ này.'), findsOneWidget);
      expect(find.byType(BudgetProgressCard), findsNothing);
    });

    testWidgets(
      'says so when the budgets could not be loaded, even if an old list is passed',
      (tester) async {
        await _pump(
          tester,
          BudgetSection(progresses: [_progress(100)], loadFailed: true),
        );

        expect(
          find.text('Không tải được ngân sách. Kéo xuống để thử lại.'),
          findsOneWidget,
        );
        expect(find.byType(BudgetProgressCard), findsNothing);
      },
    );

    testWidgets('shows only the first maxItems and counts the rest', (
      tester,
    ) async {
      final progresses = [
        for (var i = 1; i <= 5; i++)
          _progress(100, id: i, categoryName: 'Danh mục $i'),
      ];

      await _pump(tester, BudgetSection(progresses: progresses, maxItems: 3));

      expect(find.byType(BudgetProgressCard), findsNWidgets(3));
      expect(find.text('+2 ngân sách khác'), findsOneWidget);
    });

    testWidgets('does not mention other budgets when all of them are shown', (
      tester,
    ) async {
      final progresses = [
        for (var i = 1; i <= 3; i++)
          _progress(100, id: i, categoryName: 'Danh mục $i'),
      ];

      await _pump(tester, BudgetSection(progresses: progresses, maxItems: 3));
      expect(find.byType(BudgetProgressCard), findsNWidgets(3));
      expect(find.textContaining('ngân sách khác'), findsNothing);

      await _pump(tester, BudgetSection(progresses: progresses));
      expect(find.byType(BudgetProgressCard), findsNWidgets(3));
      expect(find.textContaining('ngân sách khác'), findsNothing);
    });

    testWidgets('offers "Quản lý" only when there is somewhere to go', (
      tester,
    ) async {
      var opened = 0;
      await _pump(tester, BudgetSection(progresses: [_progress(100)]));
      expect(find.text('Quản lý'), findsNothing);

      await _pump(
        tester,
        BudgetSection(progresses: [_progress(100)], onManage: () => opened++),
      );
      await tester.tap(find.text('Quản lý'));

      expect(opened, 1);
    });
  });
}
