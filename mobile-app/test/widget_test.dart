// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/di/injection_container.dart';
import 'package:finance_ai_app/main.dart';
import 'package:finance_ai_app/screens/auth/login_page.dart';

void main() {
  // DonutPayApp reads its settings from GetIt, which main() fills before runApp.
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await configureDependencies();
  });

  tearDown(() async {
    await sl.reset();
    Intl.defaultLocale = null;
  });

  testWidgets('App boots to login screen', (WidgetTester tester) async {
    await tester.pumpWidget(const DonutPayApp());
    await tester.pumpAndSettle();

    expect(find.byType(LoginScreen), findsOneWidget);
    expect(find.text('Đăng nhập'), findsOneWidget);
  });
}
