import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/dashboard/dashboard_page.dart';
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

Map<String, Object?> _tx(
  int id,
  String title,
  num amount, {
  required bool isExpense,
}) => {
  'id': id,
  'title': title,
  'amount': amount,
  'date': DateTime.now().toIso8601String(),
  'category': 'Ăn uống',
  'isExpense': isExpense,
};

void main() {
  final backend = FakeBackend();
  var transactions = <Map<String, Object?>>[];

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
    // The home screen also reads the transactions waiting to be sent; the fake keeps it away from a real database.
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

    backend.reset();
    transactions = [
      _tx(1, 'Cà phê', 45000, isExpense: true),
      _tx(2, 'Lương tháng', 5000000, isExpense: false),
    ];
    backend.on(
      'GET',
      '/api/users/me',
      (_) => okResponse({'id': 1, 'email': 'an@example.com'}),
    );
    backend.on(
      'GET',
      '/api/transactions/mine',
      (_) => okResponse(transactions),
    );
    backend.on('GET', '/api/budgets', (_) => okResponse([]));
    // The home screen also asks for the number on its notification bell.
    backend.on('GET', '/api/notifications/unread-count', (_) => okResponse(0));
  });

  // The language cubit of withAppSettings sets the default locale of intl.
  tearDown(() => Intl.defaultLocale = null);

  /// The home tab as MainScaffold shows it: in a Scaffold, whose page colour shows through the tab.
  Future<void> showDashboard(WidgetTester tester, ThemeMode mode) async {
    // Tall and wide, because the test font is much wider than a real one and the rows would overflow at phone width.
    tester.view.physicalSize = const Size(600, 3000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      withAppSettings(
        const Scaffold(body: DashboardPage()),
        settings: FakeSettingsRepository(themeMode: mode),
      ),
    );
  }

  ThemeData themeOf(WidgetTester tester) =>
      Theme.of(tester.element(find.byType(DashboardPage)));

  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    group('${mode.name} theme', () {
      testWidgets(
        'titles, secondary text, shortcuts and transactions take their colours from the theme',
        (tester) async {
          await showDashboard(tester, mode);
          await tester.pumpAndSettle();
          final theme = themeOf(tester);
          final colors = theme.colorScheme;
          final page = theme.scaffoldBackgroundColor;

          expect(
            theme.brightness,
            mode == ThemeMode.dark ? Brightness.dark : Brightness.light,
          );
          for (final title in ['an', 'Tiện ích', 'Giao dịch gần đây']) {
            expect(
              paintedColor(tester, find.text(title)),
              colors.onSurface,
              reason: title,
            );
          }
          expect(
            contrastRatio(colors.onSurface, page),
            greaterThanOrEqualTo(7),
          );
          expect(
            paintedColor(tester, find.text('Xin chào,')),
            colors.onSurfaceVariant,
          );

          // Shortcut: a surface tile with a primary icon and a secondary label.
          final savings = find.byIcon(Icons.savings_rounded);
          expect(boxColor(tester, savings), colors.surface);
          expect(paintedColor(tester, savings), colors.primary);
          expect(
            paintedColor(tester, find.text('Sổ tiết kiệm')),
            colors.onSurfaceVariant,
          );

          // Notification button.
          expect(
            tester
                .widget<CircleAvatar>(find.byType(CircleAvatar))
                .backgroundColor,
            colors.surface,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.notifications_outlined)),
            colors.onSurface,
          );

          // A transaction: title and amount in the text colour on the surface card, time in the secondary colour.
          expect(boxColor(tester, find.text('Cà phê')), colors.surface);
          expect(paintedColor(tester, find.text('Cà phê')), colors.onSurface);
          expect(
            paintedColor(tester, find.text('- ${_money.format(45000)}')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.textContaining('Hôm nay').first),
            colors.onSurfaceVariant,
          );
        },
      );

      testWidgets('the brand card keeps white text on its gradient', (
        tester,
      ) async {
        await showDashboard(tester, mode);
        await tester.pumpAndSettle();

        final card = boxDecoration(tester, find.text('Tổng tài sản hiện tại'));
        expect(card.gradient, AppTheme.purpleCardGradient);
        expect(
          paintedColor(tester, find.text(_money.format(5000000 - 45000))),
          Colors.white,
        );
        expect(
          paintedColor(tester, find.text('Tổng tài sản hiện tại')),
          Colors.white.withValues(alpha: 0.75),
        );
      });

      testWidgets('the empty list and the loading skeleton follow the theme', (
        tester,
      ) async {
        final answer = Completer<ResponseBody>();
        backend.on('GET', '/api/transactions/mine', (_) => answer.future);

        await showDashboard(tester, mode);
        // The skeleton animates while the request is pending: frames only, no settling.
        await tester.pump(const Duration(milliseconds: 100));
        final colors = themeOf(tester).colorScheme;
        final skeleton = tester
            .widgetList<Container>(
              find.descendant(
                of: find.byType(DashboardPage),
                matching: find.byType(Container),
              ),
            )
            .map((container) => container.decoration)
            .whereType<BoxDecoration>()
            .map((decoration) => decoration.color)
            .toSet();
        expect(
          skeleton,
          contains(colors.onSurfaceVariant.withValues(alpha: 0.15)),
        );

        answer.complete(okResponse(<Object>[]));
        await tester.pumpAndSettle();
        expect(
          boxColor(tester, find.text('Chưa có giao dịch nào.')),
          colors.surface,
        );
        expect(
          paintedColor(tester, find.text('Chưa có giao dịch nào.')),
          colors.onSurfaceVariant,
        );
      });
    });
  }

  testWidgets(
    'dark mode: the income and expense icons of the transactions stay apart and readable on their tiles',
    (tester) async {
      await showDashboard(tester, ThemeMode.dark);
      await tester.pumpAndSettle();
      final colors = themeOf(tester).colorScheme;

      final expense = paintedColor(tester, find.byIcon(Icons.money_off))!;
      final income = paintedColor(tester, find.byIcon(Icons.attach_money))!;

      expect(expense, isNot(income));
      for (final icon in [expense, income]) {
        final tile = over(icon.withValues(alpha: 0.15), colors.surface);
        expect(contrastRatio(icon, tile), greaterThanOrEqualTo(3));
      }
    },
  );

  testWidgets(
    'light mode keeps the colours the home tab had before the migration',
    (tester) async {
      await showDashboard(tester, ThemeMode.light);
      await tester.pumpAndSettle();

      expect(paintedColor(tester, find.text('Tiện ích')), AppTheme.textWhite);
      expect(paintedColor(tester, find.text('Xin chào,')), AppTheme.textGray);
      expect(
        boxColor(tester, find.byIcon(Icons.savings_rounded)),
        AppTheme.navyLight,
      );
      expect(
        paintedColor(tester, find.byIcon(Icons.savings_rounded)),
        AppTheme.mintAccent,
      );
      expect(boxColor(tester, find.text('Cà phê')), AppTheme.navyLight);
      expect(paintedColor(tester, find.text('Cà phê')), AppTheme.textWhite);
    },
  );
}
