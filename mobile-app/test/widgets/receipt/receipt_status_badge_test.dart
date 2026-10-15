import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/models/receipt_status.dart';
import 'package:finance_ai_app/widgets/receipt/receipt_status_badge.dart';

/// The badge takes its colours from the app theme; in light mode they are the colours it had before the themes.
Future<void> _pump(
  WidgetTester tester,
  ReceiptStatus status, {
  double? width,
  ThemeMode mode = ThemeMode.light,
}) {
  final badge = ReceiptStatusBadge(status: status);
  return tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: mode,
      home: Scaffold(
        body: Align(
          alignment: Alignment.topLeft,
          child: width == null ? badge : SizedBox(width: width, child: badge),
        ),
      ),
    ),
  );
}

Color? _labelColor(WidgetTester tester, String label) =>
    tester.widget<Text>(find.text(label)).style?.color;

void main() {
  testWidgets(
    'processing shows "Đang xử lý" with a sync icon in the warning colour',
    (tester) async {
      await _pump(tester, ReceiptStatus.processing);

      expect(find.text('Đang xử lý'), findsOneWidget);
      expect(find.byIcon(Icons.sync_rounded), findsOneWidget);
      expect(_labelColor(tester, 'Đang xử lý'), AppTheme.warningColor);
    },
  );

  testWidgets(
    'success shows "Hoàn tất" with a check icon in the positive colour',
    (tester) async {
      await _pump(tester, ReceiptStatus.success);

      expect(find.text('Hoàn tất'), findsOneWidget);
      expect(find.byIcon(Icons.check_circle_rounded), findsOneWidget);
      expect(_labelColor(tester, 'Hoàn tất'), AppTheme.positive);
    },
  );

  testWidgets(
    'failed shows "Lỗi · cần xác nhận lại" with an error icon in the danger colour',
    (tester) async {
      await _pump(tester, ReceiptStatus.failed);

      expect(find.text('Lỗi · cần xác nhận lại'), findsOneWidget);
      expect(find.byIcon(Icons.error_rounded), findsOneWidget);
      expect(
        _labelColor(tester, 'Lỗi · cần xác nhận lại'),
        AppTheme.dangerColor,
      );
    },
  );

  testWidgets(
    'in dark mode each status takes the dark palette and stays readable on its tinted pill',
    (tester) async {
      final colors = AppTheme.darkTheme.colorScheme;
      final semantic = AppTheme.darkTheme.extension<SemanticColors>()!;
      for (final (status, label, icon, color) in [
        (
          ReceiptStatus.processing,
          'Đang xử lý',
          Icons.sync_rounded,
          semantic.warningColor,
        ),
        (
          ReceiptStatus.success,
          'Hoàn tất',
          Icons.check_circle_rounded,
          semantic.successColor,
        ),
        (
          ReceiptStatus.failed,
          'Lỗi · cần xác nhận lại',
          Icons.error_rounded,
          colors.error,
        ),
      ]) {
        await _pump(tester, status, mode: ThemeMode.dark);
        await tester.pumpAndSettle();

        expect(_labelColor(tester, label), color, reason: label);
        expect(tester.widget<Icon>(find.byIcon(icon)).color, color);
        final pill =
            tester
                    .widget<Container>(
                      find.descendant(
                        of: find.byType(ReceiptStatusBadge),
                        matching: find.byType(Container),
                      ),
                    )
                    .decoration!
                as BoxDecoration;
        expect(pill.color, color.withValues(alpha: 0.12));
        final background = Color.alphaBlend(pill.color!, colors.surface);
        final la = color.computeLuminance();
        final lb = background.computeLuminance();
        expect((la + 0.05) / (lb + 0.05), greaterThan(4.5), reason: label);
      }
    },
  );

  testWidgets(
    'in a very narrow space the label is cut on one line instead of overflowing',
    (tester) async {
      await _pump(tester, ReceiptStatus.failed, width: 80);

      expect(tester.takeException(), isNull);
      expect(
        tester.getSize(find.byType(ReceiptStatusBadge)).width,
        lessThanOrEqualTo(80),
      );
    },
  );
}
