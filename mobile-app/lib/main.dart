import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/cubits/language_cubit.dart';
import 'package:finance_ai_app/cubits/theme_cubit.dart';
import 'package:finance_ai_app/di/injection_container.dart';
import 'package:finance_ai_app/l10n/app_localizations.dart';
import 'package:finance_ai_app/models/app_language.dart';
import 'package:finance_ai_app/repositories/settings_repository.dart';
import 'package:finance_ai_app/screens/auth/login_page.dart';
import 'package:finance_ai_app/screens/main_scaffold.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/core/constants/transaction_categories.dart';

final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

/// Lets code without a BuildContext (the 401 interceptor of RemoteApiHelper) show a SnackBar on whatever screen is up.
final GlobalKey<ScaffoldMessengerState> scaffoldMessengerKey =
    GlobalKey<ScaffoldMessengerState>();

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await configureDependencies();
  await SessionService.init();
  await initializeDateFormatting('vi_VN', null);
  RemoteApiHelper.instance.init();
  runApp(const DonutPayApp());
}

class DonutPayApp extends StatefulWidget {
  const DonutPayApp({super.key});

  @override
  State<DonutPayApp> createState() => _DonutPayAppState();
}

class _DonutPayAppState extends State<DonutPayApp> {
  bool _loading = true;
  bool _loggedIn = false;

  @override
  void initState() {
    super.initState();
    _check();
  }

  Future<void> _check() async {
    final loggedIn = await SessionService.isLoggedIn();
    final ok = AppConfig.isFull
        ? (loggedIn && SessionService.currentToken != null)
        : loggedIn;
    if (ok) {
      try {
        await TransactionCategories.loadFromApi();
      } catch (e) {
        debugPrint('Lỗi tải danh mục lúc startup: $e');
      }
    }

    if (!mounted) return;
    setState(() {
      _loading = false;
      _loggedIn = ok;
    });
  }

  @override
  Widget build(BuildContext context) {
    return MultiBlocProvider(
      providers: [
        BlocProvider(create: (_) => ThemeCubit(sl<SettingsRepository>())),
        BlocProvider(create: (_) => LanguageCubit(sl<SettingsRepository>())),
      ],
      child: _DonutPayMaterialApp(
        home: _loading
            ? const _StartupLoadingScreen()
            : _loggedIn
            ? const MainScaffold()
            : const LoginScreen(),
      ),
    );
  }
}

/// Shown while the session is checked. It is built under the MaterialApp, so it already has the saved light or dark
/// theme; the spinner takes the primary colour from the theme.
class _StartupLoadingScreen extends StatelessWidget {
  const _StartupLoadingScreen();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surface,
      body: const Center(child: CircularProgressIndicator()),
    );
  }
}

class _DonutPayMaterialApp extends StatelessWidget {
  const _DonutPayMaterialApp({required this.home});

  final Widget home;

  @override
  Widget build(BuildContext context) {
    return BlocSelector<ThemeCubit, ThemeState, ThemeMode>(
      selector: (state) => state.themeMode,
      builder: (context, themeMode) =>
          BlocSelector<LanguageCubit, LanguageState, AppLanguage>(
            selector: (state) => state.language,
            builder: (context, language) => MaterialApp(
              navigatorKey: navigatorKey,
              scaffoldMessengerKey: scaffoldMessengerKey,
              title: 'Donut Pay',
              debugShowCheckedModeBanner: false,
              theme: AppTheme.lightTheme,
              darkTheme: AppTheme.darkTheme,
              themeMode: themeMode,
              locale: language.locale,
              localizationsDelegates: AppLocalizations.localizationsDelegates,
              supportedLocales: AppLocalizations.supportedLocales,
              builder: (context, child) =>
                  AnnotatedRegion<SystemUiOverlayStyle>(
                    value: AppTheme.overlayStyleFor(
                      Theme.of(context).brightness,
                    ),
                    child: child ?? const SizedBox.shrink(),
                  ),
              home: home,
            ),
          ),
    );
  }
}
