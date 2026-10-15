import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';

import 'package:finance_ai_app/widgets/notifications/notification_bell.dart';

import '../../helpers/app_settings_scope.dart';
import '../../helpers/fake_settings_repository.dart';
import '../../helpers/theme_colors.dart';

/// Counts its own builds, to prove that a new count does not rebuild what holds the bell.
class _Holder extends StatelessWidget {
  const _Holder({required this.count, required this.onPressed});

  static int builds = 0;

  final ValueNotifier<int> count;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    builds++;
    return Scaffold(
      body: Center(
        child: NotificationBell(unreadCount: count, onPressed: onPressed),
      ),
    );
  }
}

void main() {
  tearDown(() => Intl.defaultLocale = null);

  group('badgeLabel', () {
    test('no badge when nothing is unread', () {
      expect(NotificationBell.badgeLabel(0), isNull);
      expect(NotificationBell.badgeLabel(-3), isNull);
    });

    test('the number up to 99, then 99+', () {
      expect(NotificationBell.badgeLabel(1), '1');
      expect(NotificationBell.badgeLabel(99), '99');
      expect(NotificationBell.badgeLabel(100), '99+');
      expect(NotificationBell.badgeLabel(123456), '99+');
    });
  });

  Future<ValueNotifier<int>> pumpBell(
    WidgetTester tester, {
    ThemeMode mode = ThemeMode.light,
    VoidCallback? onPressed,
  }) async {
    final count = ValueNotifier<int>(0);
    addTearDown(count.dispose);
    _Holder.builds = 0;
    await tester.pumpWidget(
      withAppSettings(
        _Holder(count: count, onPressed: onPressed ?? () {}),
        settings: FakeSettingsRepository(themeMode: mode),
      ),
    );
    return count;
  }

  Badge badge(WidgetTester tester) => tester.widget<Badge>(find.byType(Badge));

  testWidgets(
    'follows the count: hidden at 0, the number, then 99+, without rebuilding what holds it',
    (tester) async {
      final count = await pumpBell(tester);
      final buildsAtStart = _Holder.builds;

      expect(badge(tester).isLabelVisible, isFalse);

      count.value = 7;
      await tester.pump();
      expect(badge(tester).isLabelVisible, isTrue);
      expect(find.text('7'), findsOneWidget);

      count.value = 150;
      await tester.pump();
      expect(find.text('99+'), findsOneWidget);

      count.value = 0;
      await tester.pump();
      expect(badge(tester).isLabelVisible, isFalse);

      expect(_Holder.builds, buildsAtStart, reason: 'only the bell repaints');
    },
  );

  testWidgets('has a localized tooltip and calls back when tapped', (
    tester,
  ) async {
    var taps = 0;
    await pumpBell(tester, onPressed: () => taps++);

    expect(find.byTooltip('Thông báo'), findsOneWidget);
    await tester.tap(find.byType(IconButton));
    expect(taps, 1);
  });

  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    testWidgets(
      '${mode.name} theme: the icon and the badge take the theme colours',
      (tester) async {
        final count = await pumpBell(tester, mode: mode);
        count.value = 3;
        await tester.pump();
        final colors = Theme.of(tester.element(find.byType(Badge))).colorScheme;

        expect(
          paintedColor(tester, find.byIcon(Icons.notifications_outlined)),
          colors.onSurface,
        );
        // No colour of its own: the badge paints the theme's error colour with onError text.
        expect(badge(tester).backgroundColor, isNull);
        expect(badge(tester).textColor, isNull);
        expect(paintedColor(tester, find.text('3')), colors.onError);
      },
    );
  }
}
