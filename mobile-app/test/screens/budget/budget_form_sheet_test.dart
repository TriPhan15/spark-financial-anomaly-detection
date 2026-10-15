import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/models/budget_model.dart';
import 'package:finance_ai_app/screens/budget/budget_form_sheet.dart';

const List<BudgetCategory> _categories = [
  (id: 10, name: 'Ăn uống'),
  (id: 11, name: 'Di chuyển'),
];

Future<List<BudgetCategory>> _loadCategories() async => _categories;

/// Opens the sheet from a button, like the budget screen does, and reports how the sheet was closed.
Future<void> _openSheet(
  WidgetTester tester, {
  required Future<String?> Function(BudgetModel budget) onSubmit,
  BudgetModel? budget,
  bool weeklyEnabled = true,
  Future<List<BudgetCategory>> Function()? loadCategories,
  void Function(bool? result)? onClosed,
  bool settle = true,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      home: Builder(
        builder: (context) => Scaffold(
          body: Center(
            child: ElevatedButton(
              onPressed: () async {
                final result = await showModalBottomSheet<bool>(
                  context: context,
                  isScrollControlled: true,
                  builder: (_) => BudgetFormSheet(
                    budget: budget,
                    weeklyEnabled: weeklyEnabled,
                    loadCategories: loadCategories ?? _loadCategories,
                    onSubmit: onSubmit,
                  ),
                );
                onClosed?.call(result);
              },
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  // A sheet that is still loading keeps a spinner running, which pumpAndSettle would wait for forever.
  await (settle
      ? tester.pumpAndSettle()
      : tester.pump(const Duration(milliseconds: 400)));
}

Future<void> _tapSave(WidgetTester tester) async {
  await tester.ensureVisible(find.text('Lưu ngân sách'));
  await tester.tap(find.text('Lưu ngân sách'));
  await tester.pumpAndSettle();
}

ChoiceChip _chip(WidgetTester tester, String label) =>
    tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, label));

String _range(String periodType, [DateTime? day]) {
  final range = BudgetPeriodType.rangeContaining(
    periodType,
    day ?? DateTime.now(),
  );
  String show(DateTime d) =>
      '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';
  return '${show(range.start)} – ${show(range.end)}';
}

void main() {
  testWidgets(
    'offers both periods and every category, with the month and every category preselected',
    (tester) async {
      await _openSheet(tester, onSubmit: (_) async => null);

      expect(find.text('Thiết lập ngân sách'), findsOneWidget);
      expect(_chip(tester, 'Tháng').selected, isTrue);
      expect(_chip(tester, 'Tuần').selected, isFalse);
      expect(_chip(tester, BudgetModel.allCategoriesLabel).selected, isTrue);
      expect(find.widgetWithText(ChoiceChip, 'Ăn uống'), findsOneWidget);
      expect(find.widgetWithText(ChoiceChip, 'Di chuyển'), findsOneWidget);
      expect(
        find.text('Tháng này: ${_range(BudgetPeriodType.month)}'),
        findsOneWidget,
      );
    },
  );

  testWidgets('choosing the week shows this week as the period', (
    tester,
  ) async {
    await _openSheet(tester, onSubmit: (_) async => null);

    await tester.tap(find.widgetWithText(ChoiceChip, 'Tuần'));
    await tester.pumpAndSettle();

    expect(
      find.text('Tuần này: ${_range(BudgetPeriodType.week)}'),
      findsOneWidget,
    );
  });

  testWidgets(
    'an empty amount is refused with an AppDialog and nothing is sent',
    (tester) async {
      var sent = 0;
      await _openSheet(
        tester,
        onSubmit: (_) async {
          sent++;
          return null;
        },
      );

      await _tapSave(tester);

      expect(find.text('Số tiền không hợp lệ'), findsOneWidget);
      expect(sent, 0);
      await tester.tap(find.text('Đã hiểu'));
      await tester.pumpAndSettle();
      expect(find.text('Lưu ngân sách'), findsOneWidget);
    },
  );

  testWidgets('an amount of zero is refused too', (tester) async {
    var sent = 0;
    await _openSheet(
      tester,
      onSubmit: (_) async {
        sent++;
        return null;
      },
    );

    await tester.enterText(find.byType(TextField), '0');
    await _tapSave(tester);

    expect(find.text('Số tiền không hợp lệ'), findsOneWidget);
    expect(sent, 0);
  });

  testWidgets(
    'sends the chosen amount, period, category and the computed dates, then closes with true',
    (tester) async {
      BudgetModel? sent;
      bool? closedWith;
      await _openSheet(
        tester,
        onSubmit: (budget) async {
          sent = budget;
          return null;
        },
        onClosed: (result) => closedWith = result,
      );

      await tester.enterText(find.byType(TextField), '1.500.000');
      await tester.tap(find.widgetWithText(ChoiceChip, 'Tuần'));
      await tester.tap(find.widgetWithText(ChoiceChip, 'Ăn uống'));
      await tester.pumpAndSettle();
      await _tapSave(tester);

      final week = BudgetPeriodType.rangeContaining(
        BudgetPeriodType.week,
        DateTime.now(),
      );
      expect(sent, isNotNull);
      expect(sent!.id, isNull);
      expect(sent!.categoryId, 10);
      expect(sent!.categoryName, 'Ăn uống');
      expect(sent!.amount, 1500000.0);
      expect(sent!.periodType, BudgetPeriodType.week);
      expect(sent!.startDate, BudgetModel.formatApiDate(week.start));
      expect(sent!.endDate, BudgetModel.formatApiDate(week.end));

      expect(find.text('Đã lưu ngân sách'), findsOneWidget);
      expect(
        closedWith,
        isNull,
        reason: 'the sheet stays until the success dialog is dismissed',
      );
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();
      expect(closedWith, isTrue);
      expect(find.text('Lưu ngân sách'), findsNothing);
    },
  );

  testWidgets('a budget for every category is sent without a category', (
    tester,
  ) async {
    BudgetModel? sent;
    await _openSheet(
      tester,
      onSubmit: (budget) async {
        sent = budget;
        return null;
      },
    );

    await tester.enterText(find.byType(TextField), '2000000');
    await _tapSave(tester);

    expect(sent!.categoryId, isNull);
    expect(sent!.categoryName, isNull);
    expect(sent!.periodType, BudgetPeriodType.month);
    expect(sent!.toJson()['categoryId'], isNull);
  });

  testWidgets(
    'a failure reported by the screen is shown in an AppDialog and the sheet stays open',
    (tester) async {
      bool? closedWith;
      await _openSheet(
        tester,
        onSubmit: (_) async =>
            'Ngân sách cho danh mục và kỳ hạn này đã tồn tại',
        onClosed: (result) => closedWith = result,
      );

      await tester.enterText(find.byType(TextField), '2000000');
      await _tapSave(tester);

      expect(find.text('Không lưu được ngân sách'), findsOneWidget);
      expect(
        find.text('Ngân sách cho danh mục và kỳ hạn này đã tồn tại'),
        findsOneWidget,
      );
      await tester.tap(find.text('Đã hiểu'));
      await tester.pumpAndSettle();
      expect(find.text('Lưu ngân sách'), findsOneWidget);
      expect(closedWith, isNull);
    },
  );

  testWidgets(
    'offline, weekly budgets are switched off and the reason is shown',
    (tester) async {
      await _openSheet(
        tester,
        weeklyEnabled: false,
        onSubmit: (_) async => null,
      );

      expect(_chip(tester, 'Tuần').onSelected, isNull);
      expect(_chip(tester, 'Tháng').onSelected, isNotNull);
      expect(
        find.text('Ngân sách theo tuần cần kết nối máy chủ.'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'a category list that fails to load is reported, and every category stays available',
    (tester) async {
      await _openSheet(
        tester,
        loadCategories: () async => throw Exception('no network'),
        onSubmit: (_) async => null,
      );

      expect(find.text('Không tải được danh mục'), findsOneWidget);
      await tester.tap(find.text('Đã hiểu'));
      await tester.pumpAndSettle();
      expect(_chip(tester, BudgetModel.allCategoriesLabel).selected, isTrue);
    },
  );

  group('editing', () {
    const august = BudgetModel(
      id: 42,
      categoryId: 11,
      categoryName: 'Di chuyển',
      amount: 2000000,
      periodType: BudgetPeriodType.month,
      startDate: '2026-08-01',
      endDate: '2026-08-31',
    );

    testWidgets(
      'starts from the existing budget and keeps its id, category and dates',
      (tester) async {
        BudgetModel? sent;
        await _openSheet(
          tester,
          budget: august,
          onSubmit: (budget) async {
            sent = budget;
            return null;
          },
        );

        expect(find.text('Sửa ngân sách'), findsOneWidget);
        expect(
          tester.widget<TextField>(find.byType(TextField)).controller!.text,
          '2000000',
        );
        expect(_chip(tester, 'Di chuyển').selected, isTrue);
        expect(
          find.text('Kỳ áp dụng: 01/08/2026 – 31/08/2026'),
          findsOneWidget,
        );

        await tester.enterText(find.byType(TextField), '2500000');
        await _tapSave(tester);

        expect(sent!.id, 42);
        expect(sent!.categoryId, 11);
        expect(sent!.amount, 2500000.0);
        expect(sent!.startDate, '2026-08-01');
        expect(sent!.endDate, '2026-08-31');
      },
    );

    testWidgets(
      'changing the period type moves the budget to the current week',
      (tester) async {
        BudgetModel? sent;
        await _openSheet(
          tester,
          budget: august,
          onSubmit: (budget) async {
            sent = budget;
            return null;
          },
        );

        await tester.tap(find.widgetWithText(ChoiceChip, 'Tuần'));
        await tester.pumpAndSettle();
        expect(
          find.text('Tuần này: ${_range(BudgetPeriodType.week)}'),
          findsOneWidget,
        );
        await _tapSave(tester);

        final week = BudgetPeriodType.rangeContaining(
          BudgetPeriodType.week,
          DateTime.now(),
        );
        expect(sent!.id, 42);
        expect(sent!.periodType, BudgetPeriodType.week);
        expect(sent!.startDate, BudgetModel.formatApiDate(week.start));
      },
    );

    testWidgets(
      'keeps the category selectable even when the loaded list does not contain it',
      (tester) async {
        await _openSheet(
          tester,
          budget: august,
          loadCategories: () async => const [(id: 10, name: 'Ăn uống')],
          onSubmit: (_) async => null,
        );

        expect(_chip(tester, 'Di chuyển').selected, isTrue);
      },
    );
  });

  testWidgets(
    'closing the sheet while the request is still running does not touch the disposed sheet',
    (tester) async {
      final pending = Completer<String?>();
      await _openSheet(tester, onSubmit: (_) => pending.future);

      await tester.enterText(find.byType(TextField), '2000000');
      await tester.ensureVisible(find.text('Lưu ngân sách'));
      await tester.tap(find.text('Lưu ngân sách'));
      await tester.pump();
      expect(find.text('Đang lưu...'), findsOneWidget);

      Navigator.of(tester.element(find.byType(BudgetFormSheet))).pop();
      await tester.pumpAndSettle();
      pending.complete(null);
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('Đã lưu ngân sách'), findsNothing);
    },
  );

  for (final loadFails in [false, true]) {
    testWidgets(
      'closing the sheet while the categories are still loading does not touch the disposed sheet '
      '(the load ${loadFails ? 'fails' : 'succeeds'})',
      (tester) async {
        final pending = Completer<List<BudgetCategory>>();
        await _openSheet(
          tester,
          settle: false,
          loadCategories: () => pending.future,
          onSubmit: (_) async => null,
        );

        Navigator.of(tester.element(find.byType(BudgetFormSheet))).pop();
        await tester.pump(const Duration(milliseconds: 400));
        if (loadFails) {
          pending.completeError(Exception('no network'));
        } else {
          pending.complete(_categories);
        }
        await tester.pumpAndSettle();

        expect(tester.takeException(), isNull);
      },
    );
  }
}
