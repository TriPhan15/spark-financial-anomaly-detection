import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';

import '../../helpers/theme_colors.dart';

/// Pumps an empty page under the app themes in [mode] and returns a context the dialogs can be opened from.
Future<BuildContext> _host(WidgetTester tester, ThemeMode mode) async {
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

/// One kind of AppDialog: how to open it, its icon, its button and its accent in light and in dark mode.
typedef _Kind = ({
  String name,
  void Function(BuildContext context) open,
  IconData icon,
  String button,
  Color lightAccent,
  Color Function(ThemeData dark) darkAccent,
});

final List<_Kind> _kinds = [
  (
    name: 'success',
    open: (context) =>
        AppDialog.success(context, title: 'Tiêu đề', message: 'Nội dung'),
    icon: Icons.check_circle_rounded,
    button: 'OK',
    lightAccent: AppTheme.positive,
    darkAccent: (dark) => dark.extension<SemanticColors>()!.successColor,
  ),
  (
    name: 'error',
    open: (context) =>
        AppDialog.error(context, title: 'Tiêu đề', message: 'Nội dung'),
    icon: Icons.error_rounded,
    button: 'Đã hiểu',
    lightAccent: AppTheme.dangerColor,
    darkAccent: (dark) => dark.colorScheme.error,
  ),
  (
    name: 'warning',
    open: (context) =>
        AppDialog.warning(context, title: 'Tiêu đề', message: 'Nội dung'),
    icon: Icons.warning_amber_rounded,
    button: 'Đã hiểu',
    lightAccent: AppTheme.warningColor,
    darkAccent: (dark) => dark.extension<SemanticColors>()!.warningColor,
  ),
  (
    name: 'info',
    open: (context) =>
        AppDialog.info(context, title: 'Tiêu đề', message: 'Nội dung'),
    icon: Icons.info_rounded,
    button: 'OK',
    lightAccent: AppTheme.primary,
    darkAccent: (dark) => dark.colorScheme.primary,
  ),
];

/// Closes the open dialog with its confirm button.
Future<void> _close(WidgetTester tester, String button) async {
  await tester.tap(find.widgetWithText(ElevatedButton, button));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'light mode keeps the colours the dialogs had before the themes',
    (tester) async {
      final host = await _host(tester, ThemeMode.light);

      for (final kind in _kinds) {
        kind.open(host);
        await tester.pumpAndSettle();

        expect(
          materialColor(tester, find.byType(Dialog)),
          Colors.white,
          reason: kind.name,
        );
        expect(
          paintedColor(tester, find.text('Tiêu đề')),
          AppTheme.textPrimary,
        );
        expect(
          paintedColor(tester, find.text('Nội dung')),
          AppTheme.textSecondary,
        );
        expect(paintedColor(tester, find.byIcon(kind.icon)), kind.lightAccent);
        expect(
          boxColor(tester, find.byIcon(kind.icon)),
          kind.lightAccent.withValues(alpha: 0.12),
        );
        final button = find.widgetWithText(ElevatedButton, kind.button);
        expect(materialColor(tester, button), kind.lightAccent);
        expect(paintedColor(tester, find.text(kind.button)), Colors.white);

        await _close(tester, kind.button);
      }
    },
  );

  testWidgets(
    'dark mode: each dialog takes the dark palette and its button label stays readable',
    (tester) async {
      final host = await _host(tester, ThemeMode.dark);
      final dark = Theme.of(host);
      final colors = dark.colorScheme;

      for (final kind in _kinds) {
        kind.open(host);
        await tester.pumpAndSettle();
        final accent = kind.darkAccent(dark);

        expect(
          materialColor(tester, find.byType(Dialog)),
          colors.surface,
          reason: kind.name,
        );
        expect(paintedColor(tester, find.text('Tiêu đề')), colors.onSurface);
        expect(
          paintedColor(tester, find.text('Nội dung')),
          colors.onSurfaceVariant,
        );
        expect(
          paintedColor(tester, find.byIcon(kind.icon)),
          accent,
          reason: kind.name,
        );
        final halo = boxColor(tester, find.byIcon(kind.icon))!;
        expect(halo, accent.withValues(alpha: 0.12));
        expect(
          contrastRatio(accent, over(halo, colors.surface)),
          greaterThanOrEqualTo(3),
          reason: '${kind.name} icon on its halo',
        );
        final button = find.widgetWithText(ElevatedButton, kind.button);
        final label = paintedColor(tester, find.text(kind.button))!;
        expect(materialColor(tester, button), accent);
        expect(label, colors.surface);
        expect(
          contrastRatio(label, accent),
          greaterThanOrEqualTo(4.5),
          reason: '${kind.name} button label',
        );

        await _close(tester, kind.button);
      }
    },
  );

  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    testWidgets(
      '${mode.name} confirm: a neutral cancel button, the danger accent, and the answer still returned',
      (tester) async {
        final host = await _host(tester, mode);
        final colors = Theme.of(host).colorScheme;

        final accepted = AppDialog.confirm(
          host,
          title: 'Xóa giao dịch?',
          danger: true,
        );
        await tester.pumpAndSettle();

        expect(paintedColor(tester, find.text('Hủy')), colors.onSurfaceVariant);
        final cancel = tester.widget<OutlinedButton>(
          find.widgetWithText(OutlinedButton, 'Hủy'),
        );
        expect(cancel.style!.side!.resolve({})!.color, colors.outlineVariant);
        expect(
          paintedColor(tester, find.byIcon(Icons.warning_amber_rounded)),
          colors.error,
        );
        expect(
          materialColor(tester, find.widgetWithText(ElevatedButton, 'Đồng ý')),
          colors.error,
        );
        await _close(tester, 'Đồng ý');
        expect(await accepted, isTrue);

        final declined = AppDialog.confirm(host, title: 'Đăng xuất?');
        await tester.pumpAndSettle();
        expect(
          materialColor(tester, find.widgetWithText(ElevatedButton, 'Đồng ý')),
          colors.primary,
        );
        await tester.tap(find.text('Hủy'));
        await tester.pumpAndSettle();
        expect(await declined, isFalse);
        expect(find.byType(Dialog), findsNothing);
      },
    );
  }
}
