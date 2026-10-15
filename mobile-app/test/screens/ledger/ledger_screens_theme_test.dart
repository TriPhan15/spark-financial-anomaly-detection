import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/constants/transaction_categories.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/ledger/add_transaction_page.dart';
import 'package:finance_ai_app/screens/ledger/edit_transaction_page.dart';
import 'package:finance_ai_app/screens/ledger/ledger_page.dart';
import 'package:finance_ai_app/services/budget_alert_tracker.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/widgets/budget/budget_alert_presenter.dart';

import '../../helpers/app_settings_scope.dart';
import '../../helpers/fake_backend.dart';
import '../../helpers/fake_local_store.dart';
import '../../helpers/fake_settings_repository.dart';
import '../../helpers/theme_colors.dart';

final NumberFormat _money = NumberFormat.currency(
  locale: 'vi_VN',
  symbol: 'đ',
  decimalDigits: 0,
);

const List<Map<String, Object?>> _serverCategories = [
  {'id': 10, 'name': 'Ăn uống', 'type': 'EXPENSE'},
  {'id': 11, 'name': 'Di chuyển', 'type': 'EXPENSE'},
  {'id': 6, 'name': 'Thu nhập', 'type': 'INCOME'},
];

/// A fixed time in the current month: the Ledger shows the month of today, and a formatted time must not depend on
/// the minute the test runs.
final DateTime _day = DateTime(
  DateTime.now().year,
  DateTime.now().month,
  1,
  9,
  30,
);

Map<String, Object?> _tx(
  int id,
  String title,
  num amount, {
  required bool isExpense,
  String category = 'Ăn uống',
}) => {
  'id': id,
  'title': title,
  'amount': amount,
  'date': _day.toIso8601String(),
  'category': category,
  'isExpense': isExpense,
};

/// The button (elevated, text, filled...) that shows [label].
Finder _button(String label) => find
    .ancestor(
      of: find.text(label),
      matching: find.byWidgetPredicate((widget) => widget is ButtonStyleButton),
    )
    .first;

Finder _inSheet(Finder finder) =>
    find.descendant(of: find.byType(BottomSheet), matching: finder);

Finder _inDialog(Finder finder) =>
    find.descendant(of: find.byType(AlertDialog), matching: finder);

void main() {
  final backend = FakeBackend();

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
    // The Ledger also reads the transactions waiting to be sent; the fake keeps it away from a real database.
    AppRepository.instance.localStore = FakeLocalStore();
  });

  setUp(() async {
    // SharedPreferences shares one Future that must be created inside the test body (see budget_display_test.dart).
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    SharedPreferences.setMockInitialValues({});
    BudgetAlertTracker.instance.resetMemory();
    BudgetAlertPresenter.resetQueue();
    // The category lists are static, so a test that loads them would otherwise feed the next one.
    TransactionCategories.expenseSelectable.clear();
    TransactionCategories.incomeSelectable.clear();

    backend.reset();
    backend.on(
      'GET',
      '/api/transactions/mine',
      (_) => okResponse([
        _tx(1, 'Ăn trưa', 50000, isExpense: true),
        _tx(2, 'Lương tháng', 5000000, isExpense: false, category: 'Lương'),
      ]),
    );
    backend.on('GET', '/api/budgets', (_) => okResponse([]));
    backend.on('GET', '/api/categories', (_) => okResponse(_serverCategories));
  });

  // The language cubit of withAppSettings sets the default locale of intl.
  tearDown(() => Intl.defaultLocale = null);

  Future<ThemeData> show(
    WidgetTester tester,
    Widget screen,
    ThemeMode mode,
  ) async {
    // Tall and wide, because the test font is much wider than a real one.
    tester.view.physicalSize = const Size(600, 3000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      withAppSettings(
        screen,
        settings: FakeSettingsRepository(themeMode: mode),
      ),
    );
    await tester.pumpAndSettle();
    return Theme.of(tester.element(find.byWidget(screen)));
  }

  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    group('${mode.name} theme', () {
      group('LedgerPage', () {
        testWidgets(
          'page, date stepper, period, chart, cards, list and summary follow the theme',
          (tester) async {
            final theme = await show(tester, const LedgerPage(), mode);
            final colors = theme.colorScheme;
            final money = theme.extension<SemanticColors>()!;

            expect(
              materialColor(tester, find.byType(Scaffold)),
              theme.scaffoldBackgroundColor,
            );
            expect(
              paintedColor(tester, find.text('Sổ sách')),
              colors.onSurface,
            );
            expect(
              paintedColor(tester, find.byIcon(Icons.tune_rounded)),
              colors.onSurfaceVariant,
              reason: 'no filter is on',
            );
            expect(
              paintedColor(tester, find.byIcon(Icons.add_box_rounded)),
              colors.primary,
            );

            // Date stepper: a surface pill, with a shadow only where it shows.
            final stepper = boxDecoration(tester, find.text('Lịch'));
            expect(stepper.color, colors.surface);
            expect(stepper.boxShadow, AppTheme.softShadowFor(colors));
            expect(paintedColor(tester, find.text('Lịch')), colors.onSurface);
            expect(
              paintedColor(tester, find.byIcon(Icons.calendar_today_rounded)),
              colors.primary,
            );
            expect(
              boxColor(tester, find.byIcon(Icons.chevron_left_rounded)),
              colors.surface,
            );
            expect(
              paintedColor(tester, find.byIcon(Icons.chevron_left_rounded)),
              colors.onSurface,
            );

            // Period picker: a tonal pill.
            expect(
              boxColor(tester, find.text('Tháng')),
              colors.primaryContainer,
            );
            expect(
              paintedColor(tester, find.text('Tháng')),
              colors.onPrimaryContainer,
            );

            // Chart: income and expense series, grid and labels in theme text colours.
            final chart = tester.widget<BarChart>(find.byType(BarChart)).data;
            expect(chart.barGroups.last.barRods.map((rod) => rod.color), [
              money.incomeColor,
              money.expenseColor,
            ]);
            expect(
              chart.gridData.getDrawingHorizontalLine(0).color,
              colors.onSurfaceVariant.withValues(alpha: 0.15),
            );
            expect(
              paintedColor(tester, find.text('Th${DateTime.now().month}')),
              colors.onSurfaceVariant,
            );
            final legend = find.ancestor(
              of: find.text('Thu'),
              matching: find.byType(Row),
            );
            final dot = tester.widget<Container>(
              find
                  .descendant(
                    of: legend.first,
                    matching: find.byType(Container),
                  )
                  .first,
            );
            expect((dot.decoration! as BoxDecoration).color, money.incomeColor);
            expect(
              paintedColor(tester, find.text('Thu')),
              colors.onSurfaceVariant,
            );

            // Stat cards.
            final statCard = boxDecoration(tester, find.text('Thu nhập'));
            expect(statCard, AppTheme.cardDecoration(colors));
            expect(
              paintedColor(tester, find.text('Thu nhập')),
              colors.onSurfaceVariant,
            );
            expect(
              paintedColor(tester, find.text(_money.format(5000000)).first),
              colors.onSurface,
            );
            expect(
              paintedColor(tester, find.byIcon(Icons.south_west_rounded)),
              money.incomeColor,
            );
            expect(
              paintedColor(tester, find.byIcon(Icons.north_east_rounded)),
              money.expenseColor,
            );

            // Transactions: amounts in the income and expense colours on surface cards.
            expect(boxColor(tester, find.text('Ăn trưa')), colors.surface);
            expect(
              paintedColor(tester, find.text('Ăn trưa')),
              colors.onSurface,
            );
            expect(
              paintedColor(tester, find.textContaining('Ăn uống •')),
              colors.onSurfaceVariant,
            );
            expect(
              paintedColor(tester, find.text('+ ${_money.format(5000000)}')),
              money.incomeColor,
            );
            expect(
              paintedColor(tester, find.text('- ${_money.format(50000)}')),
              money.expenseColor,
            );
            if (mode == ThemeMode.dark) {
              for (final amount in [money.incomeColor, money.expenseColor]) {
                expect(
                  contrastRatio(amount, colors.surface),
                  greaterThanOrEqualTo(4.5),
                );
              }
            }

            // Summary.
            expect(
              paintedColor(tester, find.text('Tổng thu')),
              colors.onSurfaceVariant,
            );
            expect(paintedColor(tester, find.text('Số dư')), colors.onSurface);
            expect(
              paintedColor(tester, find.text(_money.format(4950000))),
              money.incomeColor,
            );
            expect(
              paintedColor(tester, find.text(_money.format(50000)).last),
              money.expenseColor,
            );
            expect(paintedColor(tester, find.text('Excel')), colors.primary);
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
              (divider.border! as Border).bottom.color,
              colors.outlineVariant,
            );
          },
        );

        testWidgets(
          'the filter sheet follows the theme, and the filter icon turns primary when a filter is on',
          (tester) async {
            final colors = (await show(
              tester,
              const LedgerPage(),
              mode,
            )).colorScheme;

            await tester.tap(find.byIcon(Icons.tune_rounded));
            await tester.pumpAndSettle();

            expect(
              materialColor(tester, find.byType(BottomSheet)),
              colors.surface,
            );
            expect(
              paintedColor(tester, find.text('Bộ lọc & Sắp xếp thông minh')),
              colors.onSurface,
            );
            expect(
              paintedColor(tester, find.text('1. Loại giao dịch')),
              colors.primary,
            );
            final apply = _button('Áp dụng bộ lọc');
            expect(materialColor(tester, apply), colors.primary);
            expect(
              paintedColor(tester, find.text('Áp dụng bộ lọc')),
              colors.onPrimary,
            );

            await tester.tap(find.text('Chỉ Khoản Chi'));
            await tester.pumpAndSettle();
            await tester.tap(apply);
            await tester.pumpAndSettle();

            expect(find.byType(BottomSheet), findsNothing);
            expect(
              paintedColor(tester, find.byIcon(Icons.tune_rounded)),
              colors.primary,
            );
          },
        );

        testWidgets(
          'the details sheet and the delete dialog follow the theme',
          (tester) async {
            final theme = await show(tester, const LedgerPage(), mode);
            final colors = theme.colorScheme;
            final money = theme.extension<SemanticColors>()!;

            await tester.tap(find.text('Ăn trưa'));
            await tester.pumpAndSettle();

            expect(
              materialColor(tester, find.byType(BottomSheet)),
              colors.surface,
            );
            expect(
              paintedColor(tester, _inSheet(find.text('Ăn trưa'))),
              colors.onSurface,
            );
            expect(
              paintedColor(tester, _inSheet(find.text('Ăn uống'))),
              colors.onSurfaceVariant,
            );
            expect(
              paintedColor(tester, find.text('Số tiền:')),
              colors.onSurfaceVariant,
            );
            expect(
              paintedColor(tester, find.text('-${_money.format(50000)}')),
              money.expenseColor,
            );
            expect(
              paintedColor(
                tester,
                find.text(DateFormat('dd/MM/yyyy - HH:mm').format(_day)),
              ),
              colors.onSurface,
            );
            expect(
              paintedColor(tester, find.byIcon(Icons.close)),
              colors.onSurfaceVariant,
            );
            final edit = _button('Sửa giao dịch');
            expect(materialColor(tester, edit), colors.primary);
            expect(
              paintedColor(tester, find.text('Sửa giao dịch')),
              colors.onPrimary,
            );
            final delete = _button('Xóa giao dịch này');
            expect(
              materialColor(tester, delete),
              colors.error.withValues(alpha: 0.1),
            );
            expect(
              paintedColor(tester, find.text('Xóa giao dịch này')),
              colors.error,
            );

            await tester.tap(delete);
            await tester.pumpAndSettle();

            expect(
              materialColor(tester, find.byType(AlertDialog)),
              colors.surface,
            );
            expect(
              paintedColor(tester, find.text('Xóa giao dịch?')),
              colors.onSurface,
            );
            expect(
              paintedColor(tester, find.textContaining('hoàn lại tiền')),
              colors.onSurfaceVariant,
            );
            expect(
              paintedColor(tester, _inDialog(find.text('Hủy'))),
              colors.onSurfaceVariant,
            );
            expect(materialColor(tester, _button('Xóa ngay')), colors.error);
            expect(paintedColor(tester, find.text('Xóa ngay')), colors.onError);
            if (mode == ThemeMode.dark) {
              expect(
                contrastRatio(colors.onError, colors.error),
                greaterThanOrEqualTo(4.5),
              );
            }

            await tester.tap(_inDialog(find.text('Hủy')));
            await tester.pumpAndSettle();
            expect(find.byType(AlertDialog), findsNothing);
          },
        );

        testWidgets('the date picker opens on the theme surface', (
          tester,
        ) async {
          final colors = (await show(
            tester,
            const LedgerPage(),
            mode,
          )).colorScheme;

          await tester.tap(find.text('Lịch'));
          await tester.pumpAndSettle();

          final picker = find.byType(DatePickerDialog);
          expect(materialColor(tester, picker), colors.surface);
          expect(
            DatePickerTheme.of(tester.element(picker)).headerForegroundColor,
            colors.onSurface,
          );

          await tester.tap(
            find
                .descendant(of: picker, matching: find.byType(TextButton))
                .first,
          );
          await tester.pumpAndSettle();
          expect(picker, findsNothing);
        });

        testWidgets(
          'the notice about a list that could not be loaded uses the warning colour',
          (tester) async {
            backend.on(
              'GET',
              '/api/transactions/mine',
              (_) => jsonResponse(500, {'code': 500, 'message': 'down'}),
            );
            final theme = await show(tester, const LedgerPage(), mode);
            final colors = theme.colorScheme;
            final money = theme.extension<SemanticColors>()!;
            final notice = find.textContaining('Không tải được danh sách');

            expect(
              boxColor(tester, notice),
              money.warningColor.withValues(alpha: 0.12),
            );
            expect(
              paintedColor(tester, find.byIcon(Icons.cloud_off_rounded)),
              money.warningColor,
            );
            expect(paintedColor(tester, notice), colors.onSurface);
          },
        );
      });

      group('AddTransactionPage', () {
        testWidgets(
          'the type toggle, amount, labels, boxes and save button follow the theme',
          (tester) async {
            final theme = await show(tester, const AddTransactionPage(), mode);
            final colors = theme.colorScheme;
            final money = theme.extension<SemanticColors>()!;
            TextStyle? amountStyle() =>
                tester.widget<TextField>(find.byType(TextField).first).style;

            // Expense is chosen at first.
            expect(boxColor(tester, find.text('Tiền chi')), colors.error);
            expect(paintedColor(tester, find.text('Tiền chi')), colors.onError);
            expect(boxColor(tester, find.text('Tiền thu')), Colors.transparent);
            expect(
              paintedColor(tester, find.text('Tiền thu')),
              colors.onSurfaceVariant,
            );
            expect(amountStyle()?.color, money.expenseColor);

            for (final label in [
              'Số tiền',
              'Danh mục',
              'Ngày giao dịch',
              'Ghi chú (Tùy chọn)',
            ]) {
              expect(
                paintedColor(tester, find.text(label)),
                colors.onSurfaceVariant,
                reason: label,
              );
            }
            expect(paintedColor(tester, find.text('đ')), colors.onSurface);
            expect(
              tester.widget<Divider>(find.byType(Divider)).color,
              colors.onSurfaceVariant.withValues(alpha: 0.3),
            );
            expect(boxColor(tester, find.text('Ăn uống')), colors.surface);
            expect(
              paintedColor(tester, find.text('Ăn uống')),
              colors.onSurface,
            );
            expect(
              boxColor(tester, find.byIcon(Icons.calendar_today_rounded)),
              colors.surface,
            );
            expect(
              paintedColor(tester, find.text('VD: Mua cafe Highlands')),
              colors.onSurfaceVariant,
            );
            final noteDecoration = tester
                .widget<InputDecorator>(
                  find.descendant(
                    of: find.byType(TextField).last,
                    matching: find.byType(InputDecorator),
                  ),
                )
                .decoration;
            expect(noteDecoration.fillColor, colors.surface);
            final save = _button('Lưu giao dịch');
            expect(materialColor(tester, save), colors.primary);
            expect(
              paintedColor(tester, find.text('Lưu giao dịch')),
              colors.onPrimary,
            );

            await tester.tap(find.text('Tiền thu'));
            await tester.pumpAndSettle();

            expect(boxColor(tester, find.text('Tiền thu')), colors.primary);
            expect(
              paintedColor(tester, find.text('Tiền thu')),
              colors.onPrimary,
            );
            expect(
              paintedColor(tester, find.text('Tiền chi')),
              colors.onSurfaceVariant,
            );
            expect(amountStyle()?.color, colors.primary);
          },
        );

        testWidgets('the category sheet and the date picker follow the theme', (
          tester,
        ) async {
          final colors = (await show(
            tester,
            const AddTransactionPage(),
            mode,
          )).colorScheme;

          await tester.tap(find.text('Ăn uống'));
          await tester.pumpAndSettle();

          expect(
            materialColor(tester, find.byType(BottomSheet)),
            colors.surface,
          );
          expect(
            paintedColor(tester, find.text('Chọn danh mục chi')),
            colors.onSurface,
          );
          final chosen = find.widgetWithText(ListTile, 'Ăn uống');
          expect(
            paintedColor(
              tester,
              find.descendant(of: chosen, matching: find.text('Ăn uống')),
            ),
            colors.onSurface,
          );
          expect(
            paintedColor(
              tester,
              find.descendant(
                of: chosen,
                matching: find.byIcon(Icons.check_rounded),
              ),
            ),
            colors.primary,
          );
          expect(
            paintedColor(tester, _inSheet(find.text('Di chuyển'))),
            colors.onSurfaceVariant,
          );

          await tester.tap(_inSheet(find.text('Ăn uống')));
          await tester.pumpAndSettle();
          await tester.tap(find.byIcon(Icons.calendar_today_rounded));
          await tester.pumpAndSettle();

          final picker = find.byType(DatePickerDialog);
          expect(materialColor(tester, picker), colors.surface);
          await tester.tap(
            find
                .descendant(of: picker, matching: find.byType(TextButton))
                .first,
          );
          await tester.pumpAndSettle();
        });
      });

      testWidgets(
        'EditTransactionPage: summary card, chips and labels follow the theme',
        (tester) async {
          final transaction = TransactionModel(
            id: '5',
            title: 'Cà phê Highlands',
            amount: 45000,
            date: DateTime(2026, 9, 20, 8, 30),
            category: 'Ăn uống',
            isExpense: true,
          );
          final colors = (await show(
            tester,
            EditTransactionPage(transaction: transaction),
            mode,
          )).colorScheme;

          expect(
            boxDecoration(tester, find.text('Khoản chi')),
            AppTheme.cardDecoration(colors),
          );
          expect(
            paintedColor(tester, find.text('Khoản chi')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('20/09/2026 08:30')),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.text('Danh mục')),
            colors.onSurfaceVariant,
          );

          ChoiceChip chip(String label) =>
              tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, label));
          expect(chip('Ăn uống').selectedColor, colors.primary);
          expect(chip('Ăn uống').backgroundColor, colors.surface);
          expect(paintedColor(tester, find.text('Ăn uống')), colors.onPrimary);
          expect(
            paintedColor(tester, find.text('Di chuyển')),
            colors.onSurface,
          );
          expect(
            chip('Di chuyển').side?.color,
            colors.onSurfaceVariant.withValues(alpha: 0.3),
          );
        },
      );
    });
  }

  testWidgets(
    'light mode keeps the colours the Ledger had before the migration',
    (tester) async {
      await show(tester, const LedgerPage(), ThemeMode.light);

      // Same white card; since batch 5 cards have no shadow (see AppTheme.cardDecoration).
      final statCard = boxDecoration(tester, find.text('Thu nhập'));
      expect(statCard.color, AppTheme.card.color);
      expect(statCard.borderRadius, AppTheme.card.borderRadius);
      expect(statCard.boxShadow, isNull);
      expect(paintedColor(tester, find.text('Thu nhập')), AppTheme.textGray);
      expect(
        paintedColor(tester, find.text('+ ${_money.format(5000000)}')),
        AppTheme.positive,
      );
      expect(
        paintedColor(tester, find.text('- ${_money.format(50000)}')),
        AppTheme.dangerColor,
      );
      final stepper = boxDecoration(tester, find.text('Lịch'));
      expect(stepper.color, Colors.white);
      expect(stepper.boxShadow, AppTheme.softShadow);
      expect(paintedColor(tester, find.text('Lịch')), AppTheme.textPrimary);
      expect(boxColor(tester, find.text('Tháng')), AppTheme.mintLight);
      expect(paintedColor(tester, find.text('Tháng')), AppTheme.primary);
      expect(
        paintedColor(tester, find.byIcon(Icons.tune_rounded)),
        AppTheme.textGray,
      );
    },
  );
}
