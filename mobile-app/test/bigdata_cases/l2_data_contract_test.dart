// Level 2: what the app sends and how it reads history. These cases use the REAL Big Data API, which rejects unknown
// fields, ignores history that is not strictly before the receipt and refuses to score fewer than 5 earlier expenses, so a
// wrong payload shows up as a failure here and not as a silently missing warning later.
// Needs level 1 green and the API running (docker compose -f docker-compose.bigdata.yml --profile api up -d fraud-api).
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

  void history(List<Map<String, Object?>> rows) {
    core.on('GET', '/api/transactions/mine', (_) => okResponse(rows));
  }

  setUpAll(() {
    RemoteApiHelper.instance.init(httpClientAdapter: core);
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    await SessionService.clearSession();
    await SessionService.setCurrentUserId(1);
    AnomalyCheckService.configureForTest(baseUrl: realFraudApi);
    core.reset();
    history(habitHistory());
  });

  Future<AnomalyResult?> lunch({double amount = 52000, DateTime? at, String category = 'Ăn uống'}) =>
      AnomalyCheckService.check(amount: amount, occurredAt: at ?? caseNow, category: category);

  test('L2-01 the payload is accepted by the strict API (no unknown fields) and is scored', () async {
    final result = await lunch();
    expect(result, isNotNull);
    expect(result!.modelVersion, isNotEmpty);
    expect(result.threshold, closeTo(0.961, 0.001));
  });

  test('L2-02 history longer than the API limit (200) is cut to the 100 most recent by the app', () async {
    history(habitHistory(days: 400));
    expect(await lunch(), isNotNull, reason: 'sending all ~460 rows would be a 422 and no result');
  });

  test('L2-03 income rows in the history are not mistaken for spending', () async {
    history(habitHistory(income: true));
    expect(await lunch(), isNull, reason: 'only incomes: the user has no spending history, so the model does not score');
  });

  test('L2-04 history that is all AFTER the receipt date is ignored by the model', () async {
    expect(await lunch(at: caseNow.subtract(const Duration(days: 100))), isNull);
  });

  test('L2-05 the order the server returns the rows in does not change the score', () async {
    final ordered = await lunch();
    history(habitHistory().reversed.toList());
    final reversed = await lunch();
    expect(reversed!.riskScore, ordered!.riskScore);
  });

  test('L2-06 zero-amount rows are dropped before sending (the API refuses amounts that are not positive)', () async {
    final rows = habitHistory();
    rows.insert(3, {...rows.first, 'id': 9001, 'amount': 0});
    history(rows);
    expect(await lunch(), isNotNull);
  });

  test('L2-07 a category the app has but the model does not know (Thú cưng) is accepted', () async {
    expect(await lunch(category: 'Thú cưng'), isNotNull);
  });

  test('L2-08 the same input twice gives the same score (the model is deterministic)', () async {
    final a = await lunch(amount: 1200000, at: DateTime(2026, 10, 6, 23, 40), category: 'Mua sắm online');
    final b = await lunch(amount: 1200000, at: DateTime(2026, 10, 6, 23, 40), category: 'Mua sắm online');
    expect(a!.riskScore, b!.riskScore);
  });

  test('L2-09 history screen: more than 1000 expenses are cut to the 1000 newest, not rejected with 422', () async {
    final rows = <Map<String, Object?>>[];
    for (var i = 0; i < 1200; i++) {
      rows.add({
        'id': i + 1,
        'title': 'Ăn uống',
        'amount': 40000 + (i % 7) * 5000,
        'date': '2026-09-${(1 + i % 28).toString().padLeft(2, '0')}T${(8 + i % 12).toString().padLeft(2, '0')}:${(i % 60).toString().padLeft(2, '0')}:00',
        'category': 'Ăn uống',
        'isExpense': true,
      });
    }
    history(rows);
    final scan = await AnomalyCheckService.scanHistory(forceRefresh: true);
    expect(scan, isNotNull);
    expect(scan!.scannedCount, 1000);
  });
}
