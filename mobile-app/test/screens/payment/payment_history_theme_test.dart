import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/screens/payment/payment_history_screen.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../../helpers/app_settings_scope.dart';
import '../../helpers/fake_backend.dart';
import '../../helpers/fake_settings_repository.dart';
import '../../helpers/theme_colors.dart';

final NumberFormat _money = NumberFormat.currency(
  locale: 'vi_VN',
  symbol: '₫',
  decimalDigits: 0,
);

Map<String, Object?> _order(
  String code,
  String type,
  String status,
  num amount,
  String provider,
) => {
  'orderCode': code,
  'type': type,
  'status': status,
  'amount': amount,
  'provider': provider,
  'createdAt': '2026-09-20T08:30:00',
};

void main() {
  final backend = FakeBackend();

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
  });

  setUp(() async {
    // SharedPreferences shares one Future that must be created inside the test body (see budget_display_test.dart).
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    SharedPreferences.setMockInitialValues({});

    backend.reset();
    backend.on(
      'GET',
      '/api/payment/mine',
      (_) => okResponse([
        _order('A1', 'DEPOSIT', 'SUCCESS', 100000, 'VNPAY'),
        _order('A2', 'WITHDRAW', 'FAILED', 50000, 'ZALOPAY'),
        _order('A3', 'DEPOSIT', 'CANCELLED', 20000, 'MOCK'),
        _order('A4', 'DEPOSIT', 'PENDING', 30000, 'VNPAY'),
      ]),
    );
  });

  tearDown(() {
    // The language cubit of withAppSettings sets the default locale of intl.
    Intl.defaultLocale = null;
    AppConfig.mode = AppMode.full;
  });

  Future<ThemeData> show(WidgetTester tester, ThemeMode mode) async {
    tester.view.physicalSize = const Size(600, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      withAppSettings(
        const PaymentHistoryScreen(),
        settings: FakeSettingsRepository(themeMode: mode),
      ),
    );
    await tester.pumpAndSettle();
    return Theme.of(tester.element(find.byType(PaymentHistoryScreen)));
  }

  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    group('${mode.name} theme', () {
      testWidgets(
        'page, orders, money flows and status chips follow the theme',
        (tester) async {
          final theme = await show(tester, mode);
          final colors = theme.colorScheme;
          final semantic = theme.extension<SemanticColors>()!;

          expect(materialColor(tester, find.byType(Scaffold)), colors.surface);
          expect(
            tester.widget<AppBar>(find.byType(AppBar)).backgroundColor,
            colors.surface,
          );
          expect(
            paintedColor(tester, find.text('Lịch sử nạp/rút')),
            colors.onSurface,
          );

          // A deposit is money coming in, a withdrawal money going out.
          expect(boxColor(tester, find.text('Mã: A1')), colors.surface);
          expect(
            paintedColor(tester, find.text('Nạp tiền').first),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('+${_money.format(100000)}')),
            semantic.incomeColor,
          );
          expect(
            paintedColor(
              tester,
              find.byIcon(Icons.arrow_downward_rounded).first,
            ),
            semantic.incomeColor,
          );
          expect(
            paintedColor(tester, find.text('-${_money.format(50000)}')),
            semantic.warningColor,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.arrow_upward_rounded)),
            semantic.warningColor,
          );

          for (final (status, color) in [
            ('SUCCESS', semantic.successColor),
            ('FAILED', colors.error),
            ('CANCELLED', colors.onSurfaceVariant),
            ('PENDING', semantic.warningColor),
          ]) {
            expect(
              paintedColor(tester, find.text(status)),
              color,
              reason: status,
            );
            expect(
              boxColor(tester, find.text(status)),
              color.withValues(alpha: 0.18),
              reason: status,
            );
            if (mode == ThemeMode.dark) {
              final chip = over(color.withValues(alpha: 0.18), colors.surface);
              expect(
                contrastRatio(color, chip),
                greaterThanOrEqualTo(4.5),
                reason: status,
              );
            }
          }

          expect(paintedColor(tester, find.text('ZALOPAY')), colors.primary);
          expect(
            paintedColor(tester, find.text('Mã: A1')),
            colors.onSurfaceVariant,
          );
          expect(paintedColor(tester, find.text('Hủy đơn')), colors.error);
          expect(
            paintedColor(tester, find.byIcon(Icons.close_rounded)),
            colors.error,
          );
        },
      );

      testWidgets('the empty list and the offline message follow the theme', (
        tester,
      ) async {
        backend.on('GET', '/api/payment/mine', (_) => okResponse([]));
        final colors = (await show(tester, mode)).colorScheme;
        expect(
          paintedColor(tester, find.text('Chưa có giao dịch nạp/rút.')),
          colors.onSurfaceVariant,
        );

        AppConfig.mode = AppMode.demo;
        await tester.pumpWidget(const SizedBox());
        await show(tester, mode);
        expect(
          paintedColor(tester, find.textContaining('chỉ có ở chế độ FULL')),
          colors.onSurfaceVariant,
        );
      });
    });
  }

  testWidgets(
    'light mode keeps the neutral colours the history had before the migration',
    (tester) async {
      await show(tester, ThemeMode.light);

      expect(materialColor(tester, find.byType(Scaffold)), AppTheme.navyDark);
      expect(boxColor(tester, find.text('Mã: A1')), AppTheme.navyLight);
      expect(
        paintedColor(tester, find.text('Nạp tiền').first),
        AppTheme.textWhite,
      );
      expect(paintedColor(tester, find.text('Mã: A1')), AppTheme.textGray);
      expect(paintedColor(tester, find.text('ZALOPAY')), AppTheme.mintAccent);
    },
  );
}
