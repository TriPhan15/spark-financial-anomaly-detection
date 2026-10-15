import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/constants/transaction_categories.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/screens/receipt_scanner/review_form_page.dart';
import 'package:finance_ai_app/screens/receipt_scanner/user_info_screen.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../../helpers/app_settings_scope.dart';
import '../../helpers/fake_backend.dart';
import '../../helpers/fake_settings_repository.dart';
import '../../helpers/theme_colors.dart';

/// A real file of the project, so that the scan can read it: it stands for the photo of the receipt.
const String _photo = 'assets/images/logo.png';

final NumberFormat _money = NumberFormat.currency(
  locale: 'vi_VN',
  symbol: 'đ',
  decimalDigits: 0,
);

void main() {
  final backend = FakeBackend();

  setUpAll(() async {
    await initializeDateFormatting('vi_VN');
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
  });

  setUp(() async {
    // SharedPreferences shares one Future that must be created inside the test body (see budget_display_test.dart).
    SharedPreferences.setMockInitialValues({});
    // Offline mode: the form does not upload the photo, so the scan is the only request.
    AppConfig.mode = AppMode.demo;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    SharedPreferences.setMockInitialValues({});
    TransactionCategories.expenseSelectable.clear();
    TransactionCategories.incomeSelectable.clear();

    backend.reset();
    backend.on(
      'POST',
      '/donut/scan',
      (_) => jsonResponse(200, {
        'seller': {
          'seller_name': 'Highlands',
          'timestamp': '20/09/2026 08:30',
          'total_cost_number': 45000,
        },
      }),
    );
    backend.on(
      'GET',
      '/api/categories',
      (_) => okResponse([
        {'id': 10, 'name': 'Ăn uống', 'type': 'EXPENSE'},
        {'id': 11, 'name': 'Di chuyển', 'type': 'EXPENSE'},
      ]),
    );
    backend.on(
      'GET',
      '/api/users/me',
      (_) => okResponse({'id': 1, 'email': 'an@example.com'}),
    );
  });

  tearDown(() {
    // The language cubit of withAppSettings sets the default locale of intl.
    Intl.defaultLocale = null;
    AppConfig.mode = AppMode.full;
  });

  Future<void> start(WidgetTester tester, Widget screen, ThemeMode mode) {
    tester.view.physicalSize = const Size(600, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    return tester.pumpWidget(
      withAppSettings(
        screen,
        settings: FakeSettingsRepository(themeMode: mode),
      ),
    );
  }

  /// The review form once the scan has answered. Reading the photo is real file I/O, which the fake clock of the test
  /// does not run: it gets real time first, then the pumps run the request and the rebuild.
  Future<ThemeData> openForm(WidgetTester tester, ThemeMode mode) async {
    await start(tester, const ReviewFormPage(imagePath: _photo), mode);
    for (var i = 0; i < 20 && find.text('Highlands').evaluate().isEmpty; i++) {
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 50)),
      );
      await tester.pump(const Duration(milliseconds: 100));
    }
    await tester.pumpAndSettle();
    expect(find.text('Highlands'), findsOneWidget);
    return Theme.of(tester.element(find.byType(ReviewFormPage)));
  }

  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    group('${mode.name} theme', () {
      testWidgets('ReviewFormPage: the analysing state follows the theme', (
        tester,
      ) async {
        await start(tester, const ReviewFormPage(imagePath: _photo), mode);
        await tester.pump();
        final theme = Theme.of(tester.element(find.byType(ReviewFormPage)));

        expect(
          materialColor(tester, find.byType(Scaffold)),
          theme.scaffoldBackgroundColor,
        );
        expect(
          tester
              .widget<CircularProgressIndicator>(
                find.byType(CircularProgressIndicator),
              )
              .color,
          isNull,
          reason: 'the spinner takes the primary colour of the theme',
        );
        expect(
          paintedColor(tester, find.text('Đang phân tích hóa đơn...')),
          theme.colorScheme.onSurface,
        );
        expect(
          paintedColor(tester, find.text('Vui lòng đợi trong giây lát')),
          theme.colorScheme.onSurfaceVariant,
        );
      });

      testWidgets(
        'ReviewFormPage: photo, fields, category, totals and the save button follow the theme',
        (tester) async {
          final theme = await openForm(tester, mode);
          final colors = theme.colorScheme;
          final semantic = theme.extension<SemanticColors>()!;

          // The photo of the receipt keeps its own colours: nothing filters or tints it.
          expect(find.byType(ColorFiltered), findsNothing);
          final photo = tester.widget<Image>(find.byType(Image));
          expect(photo.color, isNull);
          expect(photo.colorBlendMode, isNull);

          expect(
            paintedColor(tester, find.text('Trích xuất thành công')),
            colors.primary,
          );
          expect(
            paintedColor(tester, find.text('Thông tin chung')),
            colors.onSurface,
          );

          // The two text fields: fill, borders, label, prefix icon, text and cursor from the theme.
          final store = find.ancestor(
            of: find.text('Highlands'),
            matching: find.byType(TextFormField),
          );
          final decoration = tester
              .widget<InputDecorator>(
                find.descendant(
                  of: store,
                  matching: find.byType(InputDecorator),
                ),
              )
              .decoration;
          expect(decoration.fillColor, colors.surface);
          expect(
            (decoration.enabledBorder! as OutlineInputBorder).borderSide.color,
            colors.outlineVariant,
          );
          expect(
            (decoration.focusedBorder! as OutlineInputBorder).borderSide.color,
            colors.primary,
          );
          expect(
            paintedColor(tester, find.text('Tên cửa hàng')),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.byIcon(Icons.storefront)),
            colors.onSurfaceVariant,
          );
          final editable = tester.widget<EditableText>(
            find.descendant(of: store, matching: find.byType(EditableText)),
          );
          expect(editable.style.color, colors.onSurface);
          expect(editable.cursorColor, colors.primary);

          // Category box.
          final category = boxDecoration(tester, find.text('Ăn uống'));
          expect(category.color, colors.surface);
          expect((category.border! as Border).top.color, colors.outlineVariant);
          expect(paintedColor(tester, find.text('Ăn uống')), colors.onSurface);

          // Totals: the grand total is the amount spent.
          expect(materialColor(tester, find.byType(Card)), colors.surface);
          expect(
            paintedColor(tester, find.text('Tạm tính:')),
            colors.onSurfaceVariant,
          );
          expect(
            paintedColor(tester, find.text('TỔNG TIỀN:')),
            colors.onSurface,
          );
          final totals = find.text(_money.format(45000));
          expect(paintedColor(tester, totals.first), colors.onSurface);
          expect(paintedColor(tester, totals.last), semantic.expenseColor);
          final divider =
              tester
                      .widget<Container>(
                        find.descendant(
                          of: find.byType(Divider),
                          matching: find.byType(Container),
                        ),
                      )
                      .decoration!
                  as BoxDecoration;
          expect(
            (divider.border! as Border).bottom.color,
            colors.outlineVariant,
          );

          final save = find.ancestor(
            of: find.text('Xác nhận & Lưu CSDL'),
            matching: find.byType(ElevatedButton),
          );
          expect(materialColor(tester, save), colors.primary);
          expect(
            paintedColor(tester, find.text('Xác nhận & Lưu CSDL')),
            colors.onPrimary,
          );
        },
      );

      testWidgets('ReviewFormPage: the category sheet follows the theme', (
        tester,
      ) async {
        final colors = (await openForm(tester, mode)).colorScheme;

        await tester.tap(find.text('Ăn uống'));
        await tester.pumpAndSettle();

        final sheet = find.byType(BottomSheet);
        expect(materialColor(tester, sheet), colors.surface);
        expect(
          paintedColor(tester, find.text('Chọn danh mục chi')),
          colors.onSurface,
        );
        final chosen = find.widgetWithText(ListTile, 'Ăn uống');
        expect(
          paintedColor(
            tester,
            find.descendant(of: chosen, matching: find.text('Ăn uống')),
          ),
          colors.onSurface,
        );
        expect(
          paintedColor(
            tester,
            find.descendant(
              of: chosen,
              matching: find.byIcon(Icons.check_rounded),
            ),
          ),
          colors.primary,
        );
        expect(
          paintedColor(
            tester,
            find.descendant(of: sheet, matching: find.text('Di chuyển')),
          ),
          colors.onSurfaceVariant,
        );
      });

      testWidgets(
        'UserInfoScreen of the scan flow: rows and dividers follow the theme',
        (tester) async {
          AppConfig.mode = AppMode.full;
          await start(tester, const UserInfoScreen(), mode);
          await tester.pumpAndSettle();
          final colors = Theme.of(
            tester.element(find.byType(UserInfoScreen)),
          ).colorScheme;

          expect(
            paintedColor(tester, find.text('Họ và tên')),
            colors.onSurfaceVariant,
          );
          expect(paintedColor(tester, find.text('an')), colors.onSurface);
          expect(
            paintedColor(tester, find.byIcon(Icons.edit_rounded)),
            colors.primary,
          );
          expect(find.byType(Divider), findsNWidgets(2));
          for (var i = 0; i < 2; i++) {
            final line =
                tester
                        .widget<Container>(
                          find.descendant(
                            of: find.byType(Divider).at(i),
                            matching: find.byType(Container),
                          ),
                        )
                        .decoration!
                    as BoxDecoration;
            expect(
              (line.border! as Border).bottom.color,
              colors.outlineVariant,
            );
          }
        },
      );
    });
  }

  testWidgets(
    'light mode keeps the colours the review form had before the migration',
    (tester) async {
      await openForm(tester, ThemeMode.light);

      expect(
        paintedColor(tester, find.text('Trích xuất thành công')),
        AppTheme.mintAccent,
      );
      expect(paintedColor(tester, find.text('Tạm tính:')), AppTheme.textGray);
      expect(
        paintedColor(tester, find.text(_money.format(45000)).last),
        AppTheme.dangerColor,
      );
      expect(
        boxDecoration(tester, find.text('Ăn uống')).color,
        AppTheme.navyLight,
      );
    },
  );
}
