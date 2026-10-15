import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/linked/linked_services_page.dart';
import 'package:finance_ai_app/screens/profile/account_credentials_screen.dart';
import 'package:finance_ai_app/screens/profile/account_info_screen.dart';
import 'package:finance_ai_app/screens/profile/profile_page.dart';
import 'package:finance_ai_app/screens/profile/savings_page.dart';
import 'package:finance_ai_app/screens/profile/user_info_screen.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../../helpers/app_settings_scope.dart';
import '../../helpers/fake_backend.dart';
import '../../helpers/fake_local_store.dart';
import '../../helpers/fake_settings_repository.dart';
import '../../helpers/theme_colors.dart';

final NumberFormat _money = NumberFormat.currency(
  locale: 'vi_VN',
  symbol: '₫',
  decimalDigits: 0,
);

/// The button (elevated, text, filled...) that shows [label].
Finder _button(String label) => find
    .ancestor(
      of: find.text(label),
      matching: find.byWidgetPredicate((widget) => widget is ButtonStyleButton),
    )
    .first;

/// The colour a [Divider] is drawn with, whether its own or the one of the theme.
Color _dividerColor(WidgetTester tester, Finder divider) {
  final line =
      tester
              .widget<Container>(
                find.descendant(of: divider, matching: find.byType(Container)),
              )
              .decoration!
          as BoxDecoration;
  return (line.border! as Border).bottom.color;
}

/// The colours of every decorated [Container] inside what [finder] finds.
Set<Color?> _fills(WidgetTester tester, Finder finder) => tester
    .widgetList<Container>(
      find.descendant(of: finder, matching: find.byType(Container)),
    )
    .map((container) => container.decoration)
    .whereType<BoxDecoration>()
    .map((decoration) => decoration.color)
    .toSet();

void main() {
  final backend = FakeBackend();

  setUpAll(() {
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
    // ProfilePage also reads the transactions waiting to be sent; the fake keeps it away from a real database.
    AppRepository.instance.localStore = FakeLocalStore();
  });

  setUp(() async {
    // SharedPreferences shares one Future that must be created inside the test body (see budget_display_test.dart).
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    await SessionService.setTokens('access-token', 'refresh-token');
    SharedPreferences.setMockInitialValues({});

    backend.reset();
    backend.on(
      'GET',
      '/api/users/me',
      (_) => okResponse({
        'id': 1,
        'email': 'an@example.com',
        'phone': '0912345678',
      }),
    );
    backend.on('GET', '/api/transactions/mine', (_) => okResponse([]));
    backend.on(
      'GET',
      '/api/savings',
      (_) => okResponse([
        {
          'id': 1,
          'title': 'Du lịch',
          'target': 10000000,
          'current': 2500000,
          'deadline': '31/12/2026',
        },
      ]),
    );
  });

  // The language cubit of withAppSettings sets the default locale of intl.
  tearDown(() => Intl.defaultLocale = null);

  /// Pumps [screen] in [mode] without waiting for anything to load.
  Future<void> start(WidgetTester tester, Widget screen, ThemeMode mode) async {
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
  }

  ThemeData themeOf(WidgetTester tester, Type screen) =>
      Theme.of(tester.element(find.byType(screen)));

  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    group('${mode.name} theme', () {
      testWidgets(
        'ProfilePage: skeleton, header, sections, subtitles and the sign-out row follow the theme',
        (tester) async {
          // The tab as MainScaffold shows it: its transparent Scaffold lets the page colour through.
          await start(tester, const Scaffold(body: ProfilePage()), mode);
          await tester.pump();
          final colors = themeOf(tester, ProfilePage).colorScheme;
          expect(
            _fills(tester, find.byType(ProfilePage)),
            contains(colors.surface.withValues(alpha: 0.6)),
            reason: 'the loading skeleton',
          );

          await tester.pumpAndSettle();

          expect(paintedColor(tester, find.text('Cá nhân')), colors.onSurface);
          expect(paintedColor(tester, find.text('an')), colors.onSurface);
          expect(
            paintedColor(tester, find.text('an@example.com')),
            colors.onSurfaceVariant,
          );
          expect(
            tester
                .widget<CircleAvatar>(find.byType(CircleAvatar))
                .backgroundColor,
            colors.primary,
          );
          expect(paintedColor(tester, find.text('A')), colors.onPrimary);

          expect(
            paintedColor(tester, find.text('Tài khoản')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.manage_accounts_rounded)),
            colors.primary,
          );
          final section = tester.widget<Material>(
            find
                .ancestor(
                  of: find.text('Thông tin tài khoản'),
                  matching: find.byType(Material),
                )
                .first,
          );
          expect(section.color, colors.surface);
          expect(
            paintedColor(tester, find.text('Thông tin tài khoản')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.person_rounded)),
            colors.primary,
          );
          expect(
            paintedColor(tester, find.text('Tiếng Việt')),
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
            tester.widget<Divider>(find.byType(Divider).first).color,
            colors.onSurfaceVariant.withValues(alpha: 0.1),
          );
          expect(paintedColor(tester, find.text('Đăng xuất')), colors.error);
          expect(
            paintedColor(tester, find.byIcon(Icons.logout_rounded)),
            colors.error,
          );
        },
      );

      testWidgets(
        'SavingsPage: page, shortcuts, goal, progress and the add dialog follow the theme',
        (tester) async {
          await start(tester, const SavingsPage(), mode);
          final colors = themeOf(tester, SavingsPage).colorScheme;
          expect(
            _fills(tester, find.byType(SavingsPage)),
            contains(colors.surface.withValues(alpha: 0.6)),
            reason: 'the loading skeleton',
          );

          // The page waits 300 ms before it loads.
          await tester.pump(const Duration(milliseconds: 350));
          await tester.pumpAndSettle();

          expect(materialColor(tester, find.byType(Scaffold)), colors.surface);
          expect(
            tester.widget<AppBar>(find.byType(AppBar)).backgroundColor,
            Colors.transparent,
          );
          expect(
            paintedColor(tester, find.text('Sổ tiết kiệm')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.add_rounded)),
            colors.primary,
          );
          expect(
            paintedColor(tester, find.text('Tổng đã tiết kiệm')),
            Colors.white70,
            reason: 'the brand card keeps white text in both themes',
          );

          final share = find.byIcon(Icons.share_rounded);
          expect(boxColor(tester, share), colors.surface);
          expect(paintedColor(tester, share), colors.primary);
          expect(
            paintedColor(tester, find.text('Chia sẻ')),
            colors.onSurfaceVariant,
          );

          expect(boxColor(tester, find.text('Du lịch')), colors.surface);
          expect(paintedColor(tester, find.text('Du lịch')), colors.onSurface);
          expect(
            paintedColor(tester, find.text('Hạn: 31/12/2026')),
            colors.onSurfaceVariant,
          );
          final saved = find.descendant(
            of: find.byType(Dismissible),
            matching: find.text(_money.format(2500000)),
          );
          expect(paintedColor(tester, saved.first), colors.primary);
          expect(paintedColor(tester, saved.last), colors.onSurfaceVariant);
          expect(paintedColor(tester, find.text('Cập nhật')), colors.primary);
          expect(
            boxColor(tester, find.text('Cập nhật')),
            colors.primary.withValues(alpha: 0.12),
          );
          final progress = tester
              .widgetList<LinearProgressIndicator>(
                find.byType(LinearProgressIndicator),
              )
              .last;
          expect(
            progress.backgroundColor,
            colors.onSurfaceVariant.withValues(alpha: 0.2),
          );
          expect(progress.valueColor?.value, Colors.orangeAccent);

          await tester.tap(find.byIcon(Icons.add_rounded));
          await tester.pumpAndSettle();

          expect(
            materialColor(tester, find.byType(AlertDialog)),
            colors.surface,
          );
          expect(
            paintedColor(tester, find.text('Thêm mục tiêu tiết kiệm')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('Tên mục tiêu')),
            colors.onSurfaceVariant,
          );
          expect(
            tester
                .widget<InputDecorator>(find.byType(InputDecorator).first)
                .decoration
                .fillColor,
            colors.surface,
          );
          expect(
            paintedColor(tester, find.text('Hủy')),
            colors.onSurfaceVariant,
          );
          expect(materialColor(tester, _button('Thêm')), colors.primary);
          expect(paintedColor(tester, find.text('Thêm')), colors.onPrimary);

          await tester.tap(find.text('Hủy'));
          await tester.pumpAndSettle();
          expect(find.byType(AlertDialog), findsNothing);
        },
      );

      testWidgets('AccountInfoScreen: rows and divider follow the theme', (
        tester,
      ) async {
        await start(tester, const AccountInfoScreen(), mode);
        await tester.pumpAndSettle();
        final theme = themeOf(tester, AccountInfoScreen);
        final colors = theme.colorScheme;

        expect(
          materialColor(tester, find.byType(Scaffold)),
          theme.scaffoldBackgroundColor,
        );
        for (final icon in [Icons.person_outline, Icons.badge_outlined]) {
          expect(paintedColor(tester, find.byIcon(icon)), colors.primary);
        }
        for (final title in ['Tài khoản', 'Thông tin cá nhân']) {
          expect(
            paintedColor(tester, find.text(title)),
            colors.onSurface,
            reason: title,
          );
        }
        expect(
          paintedColor(tester, find.byIcon(Icons.chevron_right).first),
          colors.onSurfaceVariant,
        );
        expect(
          tester.widget<Divider>(find.byType(Divider)).color,
          colors.onSurfaceVariant.withValues(alpha: 0.1),
        );
      });

      testWidgets(
        'AccountCredentialsScreen: card rows, divider and the password form follow the theme',
        (tester) async {
          await start(tester, const AccountCredentialsScreen(userId: 1), mode);
          await tester.pumpAndSettle();
          final colors = themeOf(tester, AccountCredentialsScreen).colorScheme;

          expect(materialColor(tester, find.byType(Scaffold)), colors.surface);
          expect(
            paintedColor(tester, find.byIcon(Icons.lock_outline_rounded)),
            colors.primary,
          );
          expect(
            paintedColor(tester, find.text('Chạm vào dòng cần sửa.')),
            colors.onSurfaceVariant,
          );
          expect(materialColor(tester, find.byType(Card)), colors.surface);
          expect(
            paintedColor(tester, find.text('Tên đăng nhập')),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.text('an@example.com')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.edit_rounded).first),
            colors.primary,
          );
          expect(
            _dividerColor(tester, find.byType(Divider)),
            colors.outlineVariant,
          );

          await tester.tap(find.text('Mật khẩu'));
          await tester.pumpAndSettle();

          expect(
            paintedColor(tester, find.text('Đổi mật khẩu')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.visibility_off).first),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.text('Hủy')),
            colors.onSurfaceVariant,
          );
        },
      );

      testWidgets(
        'UserInfoScreen: rows, dividers and the edit button follow the theme',
        (tester) async {
          await start(tester, const UserInfoScreen(), mode);
          await tester.pumpAndSettle();
          final theme = themeOf(tester, UserInfoScreen);
          final colors = theme.colorScheme;

          expect(
            materialColor(tester, find.byType(Scaffold)),
            theme.scaffoldBackgroundColor,
          );
          expect(
            paintedColor(tester, find.text('Họ và tên')),
            colors.onSurfaceVariant,
          );
          expect(paintedColor(tester, find.text('an')), colors.onSurface);
          expect(
            paintedColor(tester, find.text('******5678')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.edit_rounded)),
            colors.primary,
          );
          expect(find.byType(Divider), findsNWidgets(2));
          for (var i = 0; i < 2; i++) {
            expect(
              _dividerColor(tester, find.byType(Divider).at(i)),
              colors.outlineVariant,
            );
          }
        },
      );

      testWidgets(
        'LinkedServicesPage: page, wallets, buttons and the sync dialog follow the theme',
        (tester) async {
          await start(tester, const LinkedServicesPage(), mode);
          await tester.pumpAndSettle();
          final colors = themeOf(tester, LinkedServicesPage).colorScheme;

          expect(materialColor(tester, find.byType(Scaffold)), colors.surface);
          expect(
            tester.widget<AppBar>(find.byType(AppBar)).backgroundColor,
            colors.surface,
          );
          expect(
            paintedColor(tester, find.text('Dịch vụ liên kết')),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.history_rounded)),
            colors.onSurface,
          );
          expect(
            paintedColor(tester, find.text('Ví điện tử đã liên kết')),
            colors.onSurface,
          );
          expect(
            paintedColor(
              tester,
              find.text(
                'Đồng bộ tiền từ ví vào ngân sách để quản lý chi tiêu.',
              ),
            ),
            colors.onSurfaceVariant,
          );
          expect(boxColor(tester, find.text('VNPAY')), colors.surface);
          expect(paintedColor(tester, find.text('VNPAY')), colors.onSurface);
          expect(
            paintedColor(tester, find.text('Đã liên kết').first),
            Colors.green,
            reason: 'the linked status keeps its green',
          );
          if (mode == ThemeMode.dark) {
            expect(
              contrastRatio(Colors.green, colors.surface),
              greaterThanOrEqualTo(4.5),
            );
          }
          final sync = _button('Đồng bộ');
          expect(materialColor(tester, sync), colors.primary);
          expect(
            paintedColor(tester, find.text('Đồng bộ').first),
            colors.onPrimary,
          );

          await tester.tap(sync);
          await tester.pumpAndSettle();

          expect(
            materialColor(tester, find.byType(AlertDialog)),
            colors.surface,
          );
          expect(
            paintedColor(tester, find.text('Đồng bộ từ VNPAY')),
            colors.onSurface,
          );
          expect(
            paintedColor(
              tester,
              find.text('Số tiền nạp vào ngân sách chi tiêu:'),
            ),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.text('VD: 100000')),
            colors.onSurfaceVariant,
          );

          await tester.tap(find.text('Hủy'));
          await tester.pumpAndSettle();
          expect(find.byType(AlertDialog), findsNothing);
        },
      );
    });
  }

  testWidgets(
    'light mode keeps the colours of the profile screens from before the migration',
    (tester) async {
      await start(tester, const Scaffold(body: ProfilePage()), ThemeMode.light);
      await tester.pumpAndSettle();

      expect(paintedColor(tester, find.text('Cá nhân')), AppTheme.textPrimary);
      expect(
        paintedColor(tester, find.text('an@example.com')),
        AppTheme.textGray,
      );
      expect(
        tester.widget<CircleAvatar>(find.byType(CircleAvatar)).backgroundColor,
        AppTheme.primary,
      );
      expect(paintedColor(tester, find.text('A')), Colors.white);
      expect(
        tester
            .widget<Material>(
              find
                  .ancestor(
                    of: find.text('Thông tin tài khoản'),
                    matching: find.byType(Material),
                  )
                  .first,
            )
            .color,
        AppTheme.navyLight,
      );
      expect(
        paintedColor(tester, find.text('Thông tin tài khoản')),
        AppTheme.textWhite,
      );
      expect(
        paintedColor(tester, find.byIcon(Icons.person_rounded)),
        AppTheme.mintAccent,
      );
      expect(
        paintedColor(tester, find.text('Đăng xuất')),
        AppTheme.dangerColor,
      );
    },
  );
}
