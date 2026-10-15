import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/models/wallet_model.dart';
import 'package:finance_ai_app/widgets/dialogs/dashboard_bottom_sheets.dart';

import '../../helpers/theme_colors.dart';

/// Pumps an empty page under the app themes in [mode] and returns a context the sheets can be opened from.
Future<BuildContext> _host(WidgetTester tester, ThemeMode mode) async {
  // Tall, so that a scrollable sheet builds every row.
  tester.view.physicalSize = const Size(800, 2400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  late BuildContext host;
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: mode,
      home: Scaffold(
        body: Builder(
          builder: (context) {
            host = context;
            return const SizedBox.expand();
          },
        ),
      ),
    ),
  );
  return host;
}

Color? _sheetColor(WidgetTester tester) =>
    materialColor(tester, find.byType(BottomSheet));

const _wallet = WalletModel(id: 1, name: 'Ví chính', balance: 1500000);

void main() {
  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    group('${mode.name} theme', () {
      testWidgets(
        'bank list: texts, dividers and the connect chip follow the theme, and every bank icon is readable on its tile',
        (tester) async {
          final host = await _host(tester, mode);
          final colors = Theme.of(host).colorScheme;

          DashboardBottomSheets.showBankConnect(host);
          await tester.pumpAndSettle();

          expect(_sheetColor(tester), colors.surface);
          expect(
            paintedColor(tester, find.text('Kết nối ngân hàng')),
            colors.onSurface,
          );
          expect(
            paintedColor(
              tester,
              find.text('Liên kết tài khoản ngân hàng để đồng bộ giao dịch'),
            ),
            colors.onSurfaceVariant,
          );
          expect(paintedColor(tester, find.text('MB Bank')), colors.onSurface);
          expect(
            paintedColor(tester, find.text('MBB')),
            colors.onSurfaceVariant,
          );
          expect(
            tester.widget<Divider>(find.byType(Divider).first).color,
            colors.onSurfaceVariant.withValues(alpha: 0.08),
          );
          final chip = find.text('Kết nối').first;
          expect(paintedColor(tester, chip), colors.onSurfaceVariant);
          expect(
            boxColor(tester, chip),
            colors.onSurfaceVariant.withValues(alpha: 0.1),
          );

          final icons = find.byIcon(Icons.account_balance_rounded);
          expect(icons, findsNWidgets(10));
          for (var i = 0; i < 10; i++) {
            final icon = paintedColor(tester, icons.at(i))!;
            final tile = boxColor(tester, icons.at(i))!;
            expect(tile, icon.withValues(alpha: 0.15));
            expect(
              contrastRatio(icon, over(tile, colors.surface)),
              greaterThanOrEqualTo(3),
              reason: 'bank $i',
            );
          }
        },
      );

      testWidgets(
        'money sources: texts, balance and the add button follow the theme',
        (tester) async {
          final host = await _host(tester, mode);
          final colors = Theme.of(host).colorScheme;

          DashboardBottomSheets.showBankSources(host, const [_wallet]);
          await tester.pumpAndSettle();

          expect(_sheetColor(tester), colors.surface);
          expect(
            paintedColor(tester, find.text('Nguồn tiền')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('Các ví đã liên kết với tài khoản')),
            colors.onSurfaceVariant,
          );
          expect(paintedColor(tester, find.text('Ví chính')), colors.onSurface);
          expect(
            paintedColor(tester, find.textContaining('1.500.000')),
            colors.primary,
          );
          expect(
            paintedColor(tester, find.text('Thêm nguồn tiền')),
            colors.primary,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.add_rounded)),
            colors.primary,
          );
          final add = tester.widget<ButtonStyleButton>(
            find.ancestor(
              of: find.text('Thêm nguồn tiền'),
              matching: find.byWidgetPredicate(
                (widget) => widget is ButtonStyleButton,
              ),
            ),
          );
          expect(
            add.style!.side!.resolve({})!.color,
            colors.primary.withValues(alpha: 0.4),
          );
        },
      );

      testWidgets(
        'money sources without a wallet say so in the secondary colour',
        (tester) async {
          final host = await _host(tester, mode);
          final colors = Theme.of(host).colorScheme;

          DashboardBottomSheets.showBankSources(host, const []);
          await tester.pumpAndSettle();

          expect(
            paintedColor(tester, find.text('Chưa có ví nào.')),
            colors.onSurfaceVariant,
          );
        },
      );

      testWidgets(
        'all features: primary-container tiles, labels and descriptions follow the theme',
        (tester) async {
          final host = await _host(tester, mode);
          final colors = Theme.of(host).colorScheme;

          DashboardBottomSheets.showAllFeatures(
            host,
            onSavings: () {},
            onBankSources: () {},
            onLinkedServices: () {},
          );
          await tester.pumpAndSettle();

          expect(_sheetColor(tester), colors.surface);
          expect(
            paintedColor(tester, find.text('Tất cả tính năng')),
            colors.onSurface,
          );
          for (final icon in [
            Icons.savings_rounded,
            Icons.account_balance_wallet_rounded,
            Icons.link_rounded,
          ]) {
            expect(
              boxColor(tester, find.byIcon(icon)),
              colors.primaryContainer,
            );
            expect(
              paintedColor(tester, find.byIcon(icon)),
              colors.onPrimaryContainer,
            );
          }
          expect(
            paintedColor(tester, find.text('Sổ tiết kiệm')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('Quản lý mục tiêu tiết kiệm')),
            colors.onSurfaceVariant,
          );
        },
      );
    });
  }

  testWidgets(
    'light mode keeps the colours the sheets had before, bank brand colours included',
    (tester) async {
      final host = await _host(tester, ThemeMode.light);

      DashboardBottomSheets.showBankConnect(host);
      await tester.pumpAndSettle();

      expect(_sheetColor(tester), AppTheme.navyLight);
      expect(
        paintedColor(tester, find.text('Kết nối ngân hàng')),
        AppTheme.textWhite,
      );
      expect(paintedColor(tester, find.text('MBB')), AppTheme.textGray);
      expect(
        paintedColor(tester, find.byIcon(Icons.account_balance_rounded).at(5)),
        const Color(0xFF001D7E),
        reason: 'MB Bank keeps its own colour',
      );
      expect(
        boxColor(tester, find.byIcon(Icons.account_balance_rounded).at(5)),
        const Color(0xFF001D7E).withValues(alpha: 0.15),
      );
      Navigator.pop(host);
      await tester.pumpAndSettle();

      DashboardBottomSheets.showBankSources(host, const [_wallet]);
      await tester.pumpAndSettle();
      expect(
        paintedColor(tester, find.textContaining('1.500.000')),
        AppTheme.mintAccent,
      );
      Navigator.pop(host);
      await tester.pumpAndSettle();

      DashboardBottomSheets.showAllFeatures(
        host,
        onSavings: () {},
        onBankSources: () {},
        onLinkedServices: () {},
      );
      await tester.pumpAndSettle();
      expect(
        paintedColor(tester, find.byIcon(Icons.savings_rounded)),
        AppTheme.primary,
      );
      expect(
        boxColor(tester, find.byIcon(Icons.savings_rounded)),
        AppTheme.mintLight,
        reason: 'the tile is now the primary container (was primary at 12%)',
      );
    },
  );

  testWidgets(
    'dark mode lifts the dark bank colours instead of drawing them on the dark sheet',
    (tester) async {
      final host = await _host(tester, ThemeMode.dark);
      final colors = Theme.of(host).colorScheme;

      DashboardBottomSheets.showBankConnect(host);
      await tester.pumpAndSettle();

      final mbIcon = paintedColor(
        tester,
        find.byIcon(Icons.account_balance_rounded).at(5),
      )!;
      expect(mbIcon, isNot(const Color(0xFF001D7E)));
      expect(
        contrastRatio(mbIcon, colors.surface),
        greaterThan(contrastRatio(const Color(0xFF001D7E), colors.surface)),
      );
    },
  );
}
