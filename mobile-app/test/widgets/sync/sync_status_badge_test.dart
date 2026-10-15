import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/models/sync_status.dart';
import 'package:finance_ai_app/widgets/sync/sync_status_badge.dart';

import '../../helpers/theme_colors.dart';

/// The badge takes its colours from the app theme; in light mode they are the colours it had before the themes.
Future<void> _pump(
  WidgetTester tester,
  SyncStatus status, {
  double? width,
  ThemeMode mode = ThemeMode.light,
}) {
  final badge = SyncStatusBadge(status: status);
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
    'pending shows "Chờ gửi" with an upload icon in the warning colour',
    (tester) async {
      await _pump(tester, SyncStatus.pending);

      expect(find.text('Chờ gửi'), findsOneWidget);
      expect(find.byIcon(Icons.cloud_upload_outlined), findsOneWidget);
      expect(_labelColor(tester, 'Chờ gửi'), AppTheme.warningColor);
    },
  );

  testWidgets(
    'error shows "Gửi lỗi" with a cloud-off icon in the danger colour',
    (tester) async {
      await _pump(tester, SyncStatus.error);

      expect(find.text('Gửi lỗi'), findsOneWidget);
      expect(find.byIcon(Icons.cloud_off_rounded), findsOneWidget);
      expect(_labelColor(tester, 'Gửi lỗi'), AppTheme.dangerColor);
    },
  );

  testWidgets(
    'synced shows "Đã đồng bộ" with a check icon in the positive colour',
    (tester) async {
      await _pump(tester, SyncStatus.synced);

      expect(find.text('Đã đồng bộ'), findsOneWidget);
      expect(find.byIcon(Icons.cloud_done_rounded), findsOneWidget);
      expect(_labelColor(tester, 'Đã đồng bộ'), AppTheme.positive);
    },
  );

  testWidgets(
    'in dark mode each status takes the dark palette and stays readable on its tinted pill',
    (tester) async {
      final colors = AppTheme.darkTheme.colorScheme;
      final semantic = AppTheme.darkTheme.extension<SemanticColors>()!;
      for (final (status, label, icon, color) in [
        (
          SyncStatus.pending,
          'Chờ gửi',
          Icons.cloud_upload_outlined,
          semantic.warningColor,
        ),
        (
          SyncStatus.synced,
          'Đã đồng bộ',
          Icons.cloud_done_rounded,
          semantic.successColor,
        ),
        (SyncStatus.error, 'Gửi lỗi', Icons.cloud_off_rounded, colors.error),
      ]) {
        await _pump(tester, status, mode: ThemeMode.dark);
        await tester.pumpAndSettle();

        expect(_labelColor(tester, label), color, reason: label);
        expect(tester.widget<Icon>(find.byIcon(icon)).color, color);
        final pill = boxDecoration(tester, find.text(label));
        expect(pill.color, color.withValues(alpha: 0.12));
        expect(
          contrastRatio(color, over(pill.color!, colors.surface)),
          greaterThan(4.5),
          reason: label,
        );
      }
    },
  );

  testWidgets(
    'in a very narrow space the label is cut on one line instead of overflowing',
    (tester) async {
      await _pump(tester, SyncStatus.error, width: 60);

      expect(tester.takeException(), isNull);
      expect(
        tester.getSize(find.byType(SyncStatusBadge)).width,
        lessThanOrEqualTo(60),
      );
    },
  );
}
