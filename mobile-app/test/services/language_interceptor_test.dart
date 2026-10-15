import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/models/app_language.dart';
import 'package:finance_ai_app/repositories/settings_repository.dart';
import 'package:finance_ai_app/repositories/settings_repository_impl.dart';
import 'package:finance_ai_app/services/language_interceptor.dart';

import '../helpers/fake_backend.dart';
import '../helpers/fake_settings_repository.dart';

void main() {
  const path = '/ping';
  late FakeBackend backend;

  setUp(() {
    backend = FakeBackend()..on('GET', path, (_) => okResponse(null));
  });

  Dio clientFor(SettingsRepository settings) =>
      Dio(BaseOptions(baseUrl: 'https://api.test'))
        ..httpClientAdapter = backend
        ..interceptors.add(LanguageInterceptor(settings));

  List<Object?> sentLanguages() => backend.requests
      .map((r) => r.headers[LanguageInterceptor.acceptLanguageHeader])
      .toList();

  test('sends the language of the app as Accept-Language', () async {
    await clientFor(FakeSettingsRepository()).get(path);

    expect(sentLanguages(), ['vi-VN']);
  });

  test(
    'reads the language for every request, so a change applies to the next one',
    () async {
      final settings = FakeSettingsRepository();
      final dio = clientFor(settings);

      await dio.get(path);
      settings.language = AppLanguage.english;
      await dio.get(path);

      expect(sentLanguages(), ['vi-VN', 'en-US']);
      expect(settings.localeTagReads, 2);
    },
  );

  test('keeps the other headers of the request', () async {
    await clientFor(
      FakeSettingsRepository(),
    ).get(path, options: Options(headers: {'Authorization': 'Bearer t'}));

    expect(backend.requests.single.headers['Authorization'], 'Bearer t');
  });

  test(
    'with the real repository the next request already carries a language whose write has not finished',
    () async {
      SharedPreferences.setMockInitialValues({
        SettingsRepositoryImpl.languageKey: 'vi',
      });
      final settings = SettingsRepositoryImpl(
        await SharedPreferences.getInstance(),
      );
      final dio = clientFor(settings);

      final write = settings.setLanguage(AppLanguage.english);
      await dio.get(path);
      await write;

      expect(sentLanguages(), ['en-US']);
    },
  );
}
