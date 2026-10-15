import 'package:dio/dio.dart';

import 'package:finance_ai_app/repositories/settings_repository.dart';

class LanguageInterceptor extends Interceptor {
  const LanguageInterceptor(this._settings);

  static const String acceptLanguageHeader = 'Accept-Language';

  final SettingsRepository _settings;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    options.headers[acceptLanguageHeader] = _settings.localeTag;
    handler.next(options);
  }
}
