import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/models/spending_range.dart';
import 'package:finance_ai_app/widgets/ai/spending_range_filter.dart';

const String _weekLabel = 'Tuần này';
const String _monthLabel = 'Tháng này';
const String _customLabel = 'Tùy chọn';
const String _refreshLabel = 'Làm mới';

final DateTime _wednesday = DateTime(2026, 9, 23);
final SpendingRange _pickedRange = SpendingRange.custom(
  DateTime(2026, 8, 1),
  DateTime(2026, 8, 15),
);

void main() {
  late List<SpendingPeriod> tappedPeriods;
  late int refreshTaps;

  setUp(() {
    tappedPeriods = [];
    refreshTaps = 0;
  });

  Future<void> pumpFilter(
    WidgetTester tester, {
    SpendingPeriod period = SpendingPeriod.month,
    bool isRefreshing = false,
  }) {
    return tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SpendingRangeFilter(
            range: SpendingRange.of(period, _wednesday, custom: _pickedRange),
            isRefreshing: isRefreshing,
            onPeriodSelected: tappedPeriods.add,
            onRefresh: () => refreshTaps++,
          ),
        ),
      ),
    );
  }

  bool isSelected(WidgetTester tester, String label) => tester
      .widget<ChoiceChip>(find.widgetWithText(ChoiceChip, label))
      .selected;

  TextButton refreshButton(WidgetTester tester) =>
      tester.widget<TextButton>(find.widgetWithText(TextButton, _refreshLabel));

  group('period options', () {
    testWidgets('offers this week, this month and a custom range', (
      tester,
    ) async {
      await pumpFilter(tester);

      expect(find.text(_weekLabel), findsOneWidget);
      expect(find.text(_monthLabel), findsOneWidget);
      expect(find.text(_customLabel), findsOneWidget);
      expect(find.byType(ChoiceChip), findsNWidgets(3));
    });

    testWidgets('marks only the option of the period on screen', (
      tester,
    ) async {
      final optionOf = {
        SpendingPeriod.week: _weekLabel,
        SpendingPeriod.month: _monthLabel,
        SpendingPeriod.custom: _customLabel,
      };

      for (final entry in optionOf.entries) {
        await pumpFilter(tester, period: entry.key);

        for (final option in optionOf.values) {
          expect(
            isSelected(tester, option),
            option == entry.value,
            reason: '${entry.key} on screen, option $option',
          );
        }
      }
    });

    testWidgets(
      'reports the option the user taps, the one already selected included',
      (tester) async {
        await pumpFilter(tester);

        await tester.tap(find.text(_weekLabel));
        await tester.tap(find.text(_monthLabel));
        await tester.tap(find.text(_customLabel));

        expect(tappedPeriods, [
          SpendingPeriod.week,
          SpendingPeriod.month,
          SpendingPeriod.custom,
        ]);
      },
    );
  });

  group('days of the range', () {
    testWidgets('shows the first and the last day of the week', (tester) async {
      await pumpFilter(tester, period: SpendingPeriod.week);

      expect(find.text('21/09/2026 - 27/09/2026'), findsOneWidget);
    });

    testWidgets('shows the first and the last day of the month', (
      tester,
    ) async {
      await pumpFilter(tester);

      expect(find.text('01/09/2026 - 30/09/2026'), findsOneWidget);
    });

    testWidgets('shows the days the user picked', (tester) async {
      await pumpFilter(tester, period: SpendingPeriod.custom);

      expect(find.text('01/08/2026 - 15/08/2026'), findsOneWidget);
    });
  });

  group('refresh button', () {
    testWidgets('reports a tap', (tester) async {
      await pumpFilter(tester);

      await tester.tap(find.text(_refreshLabel));

      expect(refreshTaps, 1);
      expect(tappedPeriods, isEmpty);
    });

    testWidgets(
      'shows a refresh icon and no spinner while nothing is running',
      (tester) async {
        await pumpFilter(tester);

        expect(find.byIcon(Icons.refresh_rounded), findsOneWidget);
        expect(find.byType(CircularProgressIndicator), findsNothing);
        expect(refreshButton(tester).onPressed, isNotNull);
      },
    );

    testWidgets(
      'shows a spinner instead of the icon and cannot be pressed while the analysis runs',
      (tester) async {
        await pumpFilter(tester, isRefreshing: true);

        expect(find.byType(CircularProgressIndicator), findsOneWidget);
        expect(find.byIcon(Icons.refresh_rounded), findsNothing);
        expect(refreshButton(tester).onPressed, isNull);

        await tester.tap(find.text(_refreshLabel));

        expect(refreshTaps, 0);
      },
    );

    testWidgets('the options stay usable while the analysis runs', (
      tester,
    ) async {
      await pumpFilter(tester, isRefreshing: true);

      await tester.tap(find.text(_weekLabel));

      expect(tappedPeriods, [SpendingPeriod.week]);
    });
  });
}
