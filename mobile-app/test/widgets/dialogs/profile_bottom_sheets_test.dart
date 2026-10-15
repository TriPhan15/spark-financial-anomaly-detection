import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/models/wallet_model.dart';
import 'package:finance_ai_app/widgets/dialogs/profile_bottom_sheets.dart';

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

Color? _dialogColor(WidgetTester tester) =>
    materialColor(tester, find.byType(AlertDialog));

/// The decoration a text field is drawn with, once the theme's inputDecorationTheme is applied.
InputDecoration _fieldDecoration(WidgetTester tester, Finder field) => tester
    .widget<InputDecorator>(
      find.descendant(of: field, matching: find.byType(InputDecorator)),
    )
    .decoration;

final List<TransactionModel> _transactions = [
  TransactionModel(
    id: '1',
    title: 'Cà phê',
    amount: 45000,
    date: DateTime(2026, 9, 20, 8, 30),
    category: 'Ăn uống',
    isExpense: true,
  ),
  TransactionModel(
    id: '2',
    title: 'Lương',
    amount: 10000000,
    date: DateTime(2026, 9, 1, 9, 0),
    category: 'Lương',
    isExpense: false,
  ),
];

const _wallet = WalletModel(id: 1, name: 'Ví chính', balance: 1500000);

void main() {
  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    group('${mode.name} theme', () {
      testWidgets(
        'notification settings: surface sheet, row titles and the save button follow the theme',
        (tester) async {
          final host = await _host(tester, mode);
          final colors = Theme.of(host).colorScheme;

          ProfileBottomSheets.showNotificationSettings(host);
          await tester.pumpAndSettle();

          expect(_sheetColor(tester), colors.surface);
          expect(
            paintedColor(tester, find.text('Cài đặt thông báo')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('Thông báo đẩy')),
            colors.onSurface,
          );
          final save = find.widgetWithText(ElevatedButton, 'Lưu');
          expect(materialColor(tester, save), colors.primary);
          expect(paintedColor(tester, find.text('Lưu')), colors.onPrimary);
        },
      );

      testWidgets('money sources: wallet name and balance follow the theme', (
        tester,
      ) async {
        final host = await _host(tester, mode);
        final colors = Theme.of(host).colorScheme;

        ProfileBottomSheets.showBankSources(host, const [_wallet]);
        await tester.pumpAndSettle();

        expect(_sheetColor(tester), colors.surface);
        expect(paintedColor(tester, find.text('Nguồn tiền')), colors.onSurface);
        expect(paintedColor(tester, find.text('Ví chính')), colors.onSurface);
        expect(
          paintedColor(tester, find.textContaining('1.500.000')),
          colors.primary,
        );
      });

      testWidgets(
        'money sources without a wallet say so in the secondary colour',
        (tester) async {
          final host = await _host(tester, mode);
          final colors = Theme.of(host).colorScheme;

          ProfileBottomSheets.showBankSources(host, const []);
          await tester.pumpAndSettle();

          expect(
            paintedColor(tester, find.text('Chưa có ví nào.')),
            colors.onSurfaceVariant,
          );
        },
      );

      testWidgets(
        'activity log: titles, dates and the expense and income amounts follow the theme',
        (tester) async {
          final host = await _host(tester, mode);
          final theme = Theme.of(host);
          final colors = theme.colorScheme;

          ProfileBottomSheets.showActivityLog(host, _transactions);
          await tester.pumpAndSettle();

          expect(_sheetColor(tester), colors.surface);
          expect(
            paintedColor(tester, find.text('Lịch sử hoạt động (2)')),
            colors.onSurface,
          );
          expect(paintedColor(tester, find.text('Cà phê')), colors.onSurface);
          expect(
            paintedColor(tester, find.text('20/09/2026 08:30')),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.textContaining('45.000')),
            theme.extension<SemanticColors>()!.expenseColor,
          );
          expect(
            paintedColor(tester, find.textContaining('10.000.000')),
            colors.primary,
          );
          // The transaction icons keep the colours of the model, readable on both surfaces.
          final expenseIcon = paintedColor(
            tester,
            find.byIcon(Icons.money_off),
          )!;
          expect(expenseIcon, _transactions.first.color);
          expect(
            contrastRatio(expenseIcon, colors.surface),
            greaterThanOrEqualTo(3),
          );
        },
      );

      testWidgets(
        'security menu: primary-container tiles, subtitles, chevrons and divider follow the theme',
        (tester) async {
          final host = await _host(tester, mode);
          final colors = Theme.of(host).colorScheme;

          ProfileBottomSheets.showSecurityMenu(host, _transactions);
          await tester.pumpAndSettle();

          expect(_sheetColor(tester), colors.surface);
          expect(
            paintedColor(tester, find.text('Bảo mật & Quyền riêng tư')),
            colors.onSurface,
          );
          for (final icon in [Icons.pin_rounded, Icons.history_rounded]) {
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
            paintedColor(tester, find.text('Tạo / Đổi mã PIN')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('Bảo vệ ứng dụng bằng mã 4 số')),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(
              tester,
              find.byIcon(Icons.chevron_right_rounded).first,
            ),
            colors.onSurfaceVariant.withValues(alpha: 0.5),
          );
          expect(
            tester.widget<Divider>(find.byType(Divider)).color,
            colors.onSurfaceVariant.withValues(alpha: 0.1),
          );
        },
      );

      testWidgets(
        'PIN dialog: surface dialog, outlined fields from the theme, cancel and save buttons',
        (tester) async {
          final host = await _host(tester, mode);
          final colors = Theme.of(host).colorScheme;

          ProfileBottomSheets.showPinSetup(host);
          await tester.pumpAndSettle();

          expect(_dialogColor(tester), colors.surface);
          expect(
            paintedColor(tester, find.text('Tạo / Đổi mã PIN')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('Mã PIN mới')),
            colors.onSurfaceVariant,
          );
          final field = _fieldDecoration(tester, find.byType(TextField).first);
          expect(field.fillColor, colors.surface);
          expect(
            field.enabledBorder?.borderSide.color,
            colors.outlineVariant,
            reason: 'the outline sets the field apart from the dialog',
          );
          expect(
            paintedColor(tester, find.text('Hủy')),
            colors.onSurfaceVariant,
          );
          final save = find.widgetWithText(ElevatedButton, 'Lưu');
          expect(materialColor(tester, save), colors.primary);
          expect(paintedColor(tester, find.text('Lưu')), colors.onPrimary);
        },
      );

      testWidgets(
        'help: topics in the primary colour, text in the secondary one',
        (tester) async {
          final host = await _host(tester, mode);
          final colors = Theme.of(host).colorScheme;

          ProfileBottomSheets.showHelp(host);
          await tester.pumpAndSettle();

          expect(_sheetColor(tester), colors.surface);
          expect(
            paintedColor(tester, find.text('Hướng dẫn sử dụng')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('📊 Dashboard')),
            colors.primary,
          );
          expect(
            paintedColor(
              tester,
              find.text(
                'Xem tổng quan tài sản, thu chi tháng và biểu đồ dòng tiền.',
              ),
            ),
            colors.onSurfaceVariant,
          );
        },
      );

      testWidgets(
        'feedback dialog: surface dialog, secondary texts, themed field and the send button',
        (tester) async {
          final host = await _host(tester, mode);
          final colors = Theme.of(host).colorScheme;

          ProfileBottomSheets.showFeedback(host);
          await tester.pumpAndSettle();

          expect(_dialogColor(tester), colors.surface);
          expect(
            paintedColor(tester, find.text('Đánh giá ứng dụng ⭐')),
            colors.onSurface,
          );
          expect(
            paintedColor(
              tester,
              find.text(
                'Ý kiến của bạn sẽ giúp hệ thống ngày càng hoàn thiện hơn!',
              ),
            ),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.text('Nhập cảm nhận hoặc góp ý...')),
            colors.onSurfaceVariant,
          );
          expect(
            _fieldDecoration(tester, find.byType(TextField)).fillColor,
            colors.surface,
          );
          expect(
            paintedColor(tester, find.text('Để sau')),
            colors.onSurfaceVariant,
          );
          final send = find.widgetWithText(ElevatedButton, 'Gửi ngay');
          expect(materialColor(tester, send), colors.primary);
          expect(paintedColor(tester, find.text('Gửi ngay')), colors.onPrimary);
        },
      );
    });
  }

  testWidgets(
    'light mode keeps the colours the sheets and dialogs had before',
    (tester) async {
      final host = await _host(tester, ThemeMode.light);

      ProfileBottomSheets.showActivityLog(host, _transactions);
      await tester.pumpAndSettle();
      expect(_sheetColor(tester), AppTheme.navyLight);
      expect(paintedColor(tester, find.text('Cà phê')), AppTheme.textWhite);
      expect(
        paintedColor(tester, find.text('20/09/2026 08:30')),
        AppTheme.textGray,
      );
      expect(
        paintedColor(tester, find.textContaining('45.000')),
        AppTheme.dangerColor,
      );
      expect(
        paintedColor(tester, find.textContaining('10.000.000')),
        AppTheme.mintAccent,
      );
      Navigator.pop(host);
      await tester.pumpAndSettle();

      ProfileBottomSheets.showSecurityMenu(host, _transactions);
      await tester.pumpAndSettle();
      expect(
        paintedColor(tester, find.byIcon(Icons.pin_rounded)),
        AppTheme.primary,
      );
      expect(
        boxColor(tester, find.byIcon(Icons.pin_rounded)),
        AppTheme.mintLight,
        reason: 'the tile is now the primary container (was primary at 12%)',
      );
      Navigator.pop(host);
      await tester.pumpAndSettle();

      ProfileBottomSheets.showPinSetup(host);
      await tester.pumpAndSettle();
      expect(_dialogColor(tester), AppTheme.navyLight);
      expect(paintedColor(tester, find.text('Mã PIN mới')), AppTheme.textGray);
      expect(
        _fieldDecoration(tester, find.byType(TextField).first).fillColor,
        AppTheme.navyDark,
      );
      expect(
        materialColor(tester, find.widgetWithText(ElevatedButton, 'Lưu')),
        AppTheme.mintAccent,
      );
      expect(paintedColor(tester, find.text('Lưu')), AppTheme.navyDark);
    },
  );
}
