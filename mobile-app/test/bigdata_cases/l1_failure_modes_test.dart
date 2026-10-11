// Level 1, the worst situations: the Big Data service is down, slow, wrong or has too little to go on. In every one of
// them the app must act as if there were no check (AnomalyCheckService returns null) and the user must be able to save.
// Nothing in the later levels counts until these pass.
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/services/anomaly_check_service.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../helpers/fake_backend.dart';
import 'support.dart';

void main() {
  final core = FakeBackend();

  setUpAll(() {
    RemoteApiHelper.instance.init(httpClientAdapter: core);
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    core.reset();
    core.on('GET', '/api/transactions/mine', (_) => okResponse(habitHistory()));
  });

  Future<AnomalyResult?> check({double amount = 5000000, DateTime? at}) => AnomalyCheckService.check(
    amount: amount,
    occurredAt: at ?? caseNow.add(const Duration(hours: 11)),
    category: 'Mua sắm online',
  );

  test('L1-01 no FRAUD_API_URL configured: no request leaves the app and nothing is flagged', () async {
    AnomalyCheckService.configureForTest(baseUrl: '');
    expect(await check(), isNull);
    expect(core.calls, isEmpty, reason: 'the history is not even fetched');
  });

  test('L1-02 demo (offline) mode: the check is skipped', () async {
    AnomalyCheckService.configureForTest(baseUrl: realFraudApi);
    AppConfig.mode = AppMode.demo;
    addTearDown(() => AppConfig.mode = AppMode.full);
    expect(await check(), isNull);
    expect(core.calls, isEmpty);
  });

  test('L1-03 Big Data API is down (connection refused): fail-open, and fast', () async {
    AnomalyCheckService.configureForTest(baseUrl: 'http://127.0.0.1:9');
    final timer = Stopwatch()..start();
    expect(await check(), isNull);
    expect(timer.elapsedMilliseconds, lessThan(3000));
  });

  test('L1-04 Big Data API accepts the connection but never answers: gives up at the 3 s timeout', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    server.listen((request) {}); // never responds
    AnomalyCheckService.configureForTest(baseUrl: 'http://127.0.0.1:${server.port}');
    final timer = Stopwatch()..start();
    expect(await check(), isNull);
    expect(timer.elapsedMilliseconds, inInclusiveRange(2500, 4500));
  });

  test('L1-05 Big Data API answers 500: fail-open', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    server.listen((request) {
      request.response.statusCode = 500;
      request.response.close();
    });
    AnomalyCheckService.configureForTest(baseUrl: 'http://127.0.0.1:${server.port}');
    expect(await check(), isNull);
  });

  test('L1-06 Big Data API answers 200 with a body the app cannot read: fail-open', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    server.listen((request) {
      request.response.headers.contentType = ContentType.json;
      request.response.write('{"scored": true, "isAnomaly": "maybe"}');
      request.response.close();
    });
    AnomalyCheckService.configureForTest(baseUrl: 'http://127.0.0.1:${server.port}');
    expect(await check(), isNull);
  });

  test('L1-07 core-backend cannot give the history: fail-open, the model is never asked', () async {
    AnomalyCheckService.configureForTest(baseUrl: realFraudApi);
    core.on('GET', '/api/transactions/mine', (options) => throw connectionError(options));
    expect(await check(), isNull);
  });

  test('L1-08 user with no history at all (new account): not scored, no warning', () async {
    AnomalyCheckService.configureForTest(baseUrl: realFraudApi);
    core.on('GET', '/api/transactions/mine', (_) => okResponse(<Object>[]));
    expect(await check(), isNull);
  });

  test('L1-09 user with 4 earlier expenses (below the 5 the model needs), REAL model: not scored', () async {
    AnomalyCheckService.configureForTest(baseUrl: realFraudApi);
    core.on('GET', '/api/transactions/mine', (_) => okResponse(habitHistory().take(4).toList()));
    expect(await check(), isNull, reason: 'the API answers scored=false and the app treats that as no check');
  });
}
