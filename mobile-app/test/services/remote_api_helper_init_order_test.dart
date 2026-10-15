import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/di/injection_container.dart';
import 'package:finance_ai_app/models/app_language.dart';
import 'package:finance_ai_app/repositories/settings_repository.dart';
import 'package:finance_ai_app/services/language_interceptor.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';

import '../helpers/captured_logs.dart';
import '../helpers/fake_backend.dart';
import '../helpers/fake_settings_repository.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() => sl.reset());

  test(
    'init() before the settings exist sends no Accept-Language, and a later init() adds it once',
    () async {
      final backend = FakeBackend()
        ..on('GET', '/api/users/me', (_) => okResponse({'id': 1}));
      Object? sentLanguage() => backend
          .requests
          .single
          .headers[LanguageInterceptor.acceptLanguageHeader];

      await withCapturedLogs((logs) async {
        RemoteApiHelper.instance.init(httpClientAdapter: backend);
        expect(logs.single, contains('SettingsRepository'));
      });
      await RemoteApiHelper.instance.fetchMe();
      expect(sentLanguage(), isNull);

      final settings = FakeSettingsRepository(language: AppLanguage.english);
      sl.registerSingleton<SettingsRepository>(settings);
      RemoteApiHelper.instance.init();
      RemoteApiHelper.instance.init();
      backend.requests.clear();
      await RemoteApiHelper.instance.fetchMe();

      expect(sentLanguage(), 'en-US');
      expect(settings.localeTagReads, 1);
    },
  );
}
