import 'package:get_it/get_it.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/repositories/settings_repository.dart';
import 'package:finance_ai_app/repositories/settings_repository_impl.dart';

final GetIt sl = GetIt.instance;

Future<void> configureDependencies() async {
  if (sl.isRegistered<SettingsRepository>()) return;
  final preferences = await SharedPreferences.getInstance();
  // A second call may have finished registering while this one was waiting for the preferences.
  if (sl.isRegistered<SettingsRepository>()) return;
  sl.registerSingleton<SettingsRepository>(SettingsRepositoryImpl(preferences));
}
