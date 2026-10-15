import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/di/injection_container.dart';
import 'package:finance_ai_app/main.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/repositories/settings_repository_impl.dart';
import 'package:finance_ai_app/screens/main_scaffold.dart';
import 'package:finance_ai_app/services/budget_alert_tracker.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/widgets/budget/budget_alert_presenter.dart';

import '../helpers/fake_backend.dart';
import '../helpers/fake_local_store.dart';

/// The tabs in the order of the bottom bar.
const List<(String, IconData)> _tabs = [
  ('Trang chủ', Icons.home_rounded),
  ('Thống kê', Icons.bar_chart_rounded),
  ('AI', Icons.auto_awesome_rounded),
  ('Cá nhân', Icons.person_rounded),
];

Finder _inBottomBar(Finder finder) =>
    find.descendant(of: find.byType(BottomAppBar), matching: finder);

Finder _scanButtonIcon() => find.descendant(
  of: find.byType(FloatingActionButton),
  matching: find.byIcon(Icons.document_scanner_rounded),
);

/// The colour a text or an icon is painted with: both draw through a RichText.
Color? _paintedColor(WidgetTester tester, Finder finder) => tester
    .widget<RichText>(
      find.descendant(of: finder, matching: find.byType(RichText)).first,
    )
    .text
    .style
    ?.color;

Color? _materialColor(WidgetTester tester, Finder owner) => tester
    .widget<Material>(
      find.descendant(of: owner, matching: find.byType(Material)).first,
    )
    .color;

Color _bottomBarColor(WidgetTester tester) => tester
    .widget<PhysicalShape>(
      find.descendant(
        of: find.byType(BottomAppBar),
        matching: find.byType(PhysicalShape),
      ),
    )
    .color;

/// The colour the spinner paints. The painter class is private, but its valueColor is what reaches the canvas.
Color _spinnerColor(WidgetTester tester) {
  final paint = tester.widget<CustomPaint>(
    find
        .descendant(
          of: find.byType(CircularProgressIndicator),
          matching: find.byType(CustomPaint),
        )
        .first,
  );
  return (paint.painter! as dynamic).valueColor as Color;
}

void main() {
  final backend = FakeBackend();

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
    // The tabs also read the transactions waiting to be sent; the fake keeps the test away from a real database.
    AppRepository.instance.localStore = FakeLocalStore();
  });

  tearDown(() async {
    await sl.reset();
    Intl.defaultLocale = null;
  });

  /// Signed in, with [mode] saved as the theme: what main() finds on the device before runApp.
  Future<void> signInWithSavedTheme(ThemeMode mode) async {
    SharedPreferences.setMockInitialValues({
      SettingsRepositoryImpl.themeModeKey: mode.name,
      SettingsRepositoryImpl.languageKey: 'vi',
    });
    await configureDependencies();
    AppConfig.mode = AppMode.full;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    await SessionService.setTokens('access-token', 'refresh-token');
    BudgetAlertTracker.instance.resetMemory();
    BudgetAlertPresenter.resetQueue();

    backend.reset();
    backend.on('GET', '/api/categories', (_) => okResponse([]));
    backend.on(
      'GET',
      '/api/users/me',
      (_) => okResponse({'id': 1, 'email': 'an@example.com'}),
    );
    backend.on('GET', '/api/transactions/mine', (_) => okResponse([]));
    backend.on('GET', '/api/budgets', (_) => okResponse([]));
    backend.on('GET', '/api/savings', (_) => okResponse([]));
    // The home tab also asks for the number on its notification bell.
    backend.on('GET', '/api/notifications/unread-count', (_) => okResponse(0));
    backend.on(
      'POST',
      '/spade/analyze',
      (_) => jsonResponse(200, {'patterns': []}),
    );
    // A Future made here and awaited inside testWidgets never completes, so the test body gets its own instance.
    SharedPreferences.setMockInitialValues({});
  }

  /// Pumps the first frame only: the session is still being checked, so the startup loading screen shows.
  Future<void> startApp(WidgetTester tester) async {
    // Wide and tall, because the test font is much wider than a real one and the tabs would overflow at phone width.
    tester.view.physicalSize = const Size(600, 3000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(const DonutPayApp());
  }

  Future<ThemeData> openMainScaffold(WidgetTester tester) async {
    await startApp(tester);
    await tester.pumpAndSettle();
    expect(find.byType(MainScaffold), findsOneWidget);
    return Theme.of(tester.element(find.byType(MainScaffold)));
  }

  void expectSelectedTab(
    WidgetTester tester,
    ColorScheme colors,
    int selected,
  ) {
    for (var i = 0; i < _tabs.length; i++) {
      final (label, icon) = _tabs[i];
      final expected = i == selected ? colors.primary : colors.onSurfaceVariant;
      expect(
        _paintedColor(tester, _inBottomBar(find.text(label))),
        expected,
        reason: label,
      );
      expect(
        _paintedColor(tester, _inBottomBar(find.byIcon(icon))),
        expected,
        reason: label,
      );
    }
  }

  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    final brightness = mode == ThemeMode.dark
        ? Brightness.dark
        : Brightness.light;

    group('saved ${mode.name} theme', () {
      setUp(() => signInWithSavedTheme(mode));

      testWidgets('the startup loading screen already uses it', (tester) async {
        await startApp(tester);

        final colors = Theme.of(
          tester.element(find.byType(CircularProgressIndicator)),
        ).colorScheme;
        expect(colors.brightness, brightness);
        expect(_materialColor(tester, find.byType(Scaffold)), colors.surface);
        expect(_spinnerColor(tester), colors.primary);

        await tester.pumpAndSettle();
        expect(find.byType(MainScaffold), findsOneWidget);
      });

      testWidgets(
        'the page, the bottom bar, the scan button, the tabs and the system bars follow it',
        (tester) async {
          final theme = await openMainScaffold(tester);
          final colors = theme.colorScheme;

          expect(theme.brightness, brightness);
          expect(
            _materialColor(tester, find.byType(MainScaffold)),
            theme.scaffoldBackgroundColor,
          );
          expect(_bottomBarColor(tester), colors.surface);
          expect(
            _materialColor(tester, find.byType(FloatingActionButton)),
            colors.primary,
          );
          expect(_paintedColor(tester, _scanButtonIcon()), colors.onPrimary);
          expectSelectedTab(tester, colors, 0);

          await tester.tap(_inBottomBar(find.text('Thống kê')));
          await tester.pumpAndSettle();
          expectSelectedTab(tester, colors, 1);

          // MainScaffold has no app bar, so the region at the root of the app sets both system bars.
          final rootRegion = find.ancestor(
            of: find.byType(Navigator),
            matching: find.byType(AnnotatedRegion<SystemUiOverlayStyle>),
          );
          expect(
            tester
                .widget<AnnotatedRegion<SystemUiOverlayStyle>>(rootRegion)
                .value,
            AppTheme.overlayStyleFor(brightness),
          );
          expect(
            SystemChrome.latestStyle,
            AppTheme.overlayStyleFor(brightness),
          );
        },
      );

      testWidgets('the scan sheet follows it', (tester) async {
        await openMainScaffold(tester);
        await tester.tap(find.byType(FloatingActionButton));
        await tester.pumpAndSettle();

        final sheet = find.byType(BottomSheet);
        final colors = Theme.of(tester.element(sheet)).colorScheme;
        final fills = tester
            .widgetList<Container>(
              find.descendant(of: sheet, matching: find.byType(Container)),
            )
            .map((container) => container.decoration)
            .whereType<BoxDecoration>()
            .map((decoration) => decoration.color);

        expect(_materialColor(tester, sheet), colors.surface);
        for (final text in ['Quét hóa đơn bằng AI', 'Quét AI', 'Từ thư viện']) {
          expect(
            _paintedColor(
              tester,
              find.descendant(of: sheet, matching: find.text(text)),
            ),
            colors.onSurface,
            reason: text,
          );
        }
        for (final icon in [
          Icons.document_scanner_rounded,
          Icons.photo_library_rounded,
        ]) {
          expect(
            _paintedColor(
              tester,
              find.descendant(of: sheet, matching: find.byIcon(icon)),
            ),
            colors.primary,
          );
        }
        expect(
          fills,
          containsAll([
            colors.onSurfaceVariant.withValues(alpha: 0.3),
            colors.primary.withValues(alpha: 0.10),
          ]),
        );

        await tester.tapAt(const Offset(20, 20));
        await tester.pumpAndSettle();
        expect(sheet, findsNothing);
      });
    });
  }

  group('light mode', () {
    setUp(() => signInWithSavedTheme(ThemeMode.light));

    testWidgets(
      'keeps the colours the loading screen and MainScaffold had before the migration',
      (tester) async {
        await startApp(tester);
        expect(_materialColor(tester, find.byType(Scaffold)), Colors.white);
        expect(_spinnerColor(tester), AppTheme.mintAccent);

        await tester.pumpAndSettle();
        expect(
          _materialColor(tester, find.byType(MainScaffold)),
          AppTheme.bgLight,
        );
        expect(_bottomBarColor(tester), Colors.white);
        expect(
          _materialColor(tester, find.byType(FloatingActionButton)),
          AppTheme.primary,
        );
        expect(_paintedColor(tester, _scanButtonIcon()), Colors.white);
        expect(
          _paintedColor(tester, _inBottomBar(find.text('Trang chủ'))),
          AppTheme.primary,
        );
        expect(
          _paintedColor(tester, _inBottomBar(find.text('Cá nhân'))),
          AppTheme.textGray,
        );
      },
    );
  });
}
