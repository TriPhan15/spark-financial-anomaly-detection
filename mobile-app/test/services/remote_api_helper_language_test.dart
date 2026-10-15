import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/di/injection_container.dart';
import 'package:finance_ai_app/models/app_language.dart';
import 'package:finance_ai_app/repositories/settings_repository.dart';
import 'package:finance_ai_app/services/language_interceptor.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';

import '../helpers/fake_backend.dart';
import '../helpers/fake_settings_repository.dart';

const String _mePath = '/api/users/me';
const String _analyzePath = '/spade/analyze';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final backend = FakeBackend();
  final settings = FakeSettingsRepository();

  setUpAll(() {
    // The order of main(): the settings are registered before the helper is initialised.
    sl.registerSingleton<SettingsRepository>(settings);
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
    RemoteApiHelper.instance.init();
    RemoteApiHelper.instance.init(
      settingsRepository: FakeSettingsRepository(language: AppLanguage.english),
    );
  });

  tearDownAll(() => sl.reset());

  setUp(() {
    backend.reset();
    settings
      ..language = AppLanguage.vietnamese
      ..localeTagReads = 0;
    backend.on('GET', _mePath, (_) => okResponse({'id': 1}));
    backend.on(
      'POST',
      _analyzePath,
      (_) => jsonResponse(200, {'patterns': []}),
    );
  });

  Object? acceptLanguageOf(String path) => backend.requests
      .singleWhere((r) => r.path == path)
      .headers[LanguageInterceptor.acceptLanguageHeader];

  test('the core API client sends the language of the app', () async {
    await RemoteApiHelper.instance.fetchMe();

    expect(acceptLanguageOf(_mePath), 'vi-VN');
  });

  test('the AI service client sends it too', () async {
    await RemoteApiHelper.instance.analyzePatterns([]);

    expect(acceptLanguageOf(_analyzePath), 'vi-VN');
  });

  test(
    'after three init() calls the language is still read once per request, from the first repository',
    () async {
      await RemoteApiHelper.instance.fetchMe();
      await RemoteApiHelper.instance.analyzePatterns([]);

      expect(settings.localeTagReads, 2);
    },
  );

  test(
    'a language chosen between two requests applies to the next one',
    () async {
      await RemoteApiHelper.instance.fetchMe();
      await settings.setLanguage(AppLanguage.english);
      await RemoteApiHelper.instance.fetchMe();
      await RemoteApiHelper.instance.analyzePatterns([]);

      expect(
        backend.requests.map(
          (r) => r.headers[LanguageInterceptor.acceptLanguageHeader],
        ),
        ['vi-VN', 'en-US', 'en-US'],
      );
    },
  );
}
