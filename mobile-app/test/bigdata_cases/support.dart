import 'package:dio/dio.dart';
import 'package:intl/intl.dart';

import '../helpers/fake_backend.dart';

/// The Big Data API that docker-compose.bigdata.yml publishes (profile `api`). Levels 1 to 3 talk to it for real.
const String realFraudApi = 'http://127.0.0.1:8001';

/// Host of the stand-in the screen-level cases (level 4 and 5) use, so that a dialog can be provoked on demand.
const String stubFraudApi = 'http://fraud.test';

final DateFormat _iso = DateFormat("yyyy-MM-dd'T'HH:mm:ss");

/// "Now" of every case: fixed, so that history, time of day and the 24 h window never depend on the day the tests run.
final DateTime caseNow = DateTime(2026, 10, 6, 12, 15);

/// What GET /api/transactions/mine returns for a user who buys lunch (~50k VND) every day and groceries (~300k VND) at
/// the weekend: the same habit the Python end-to-end check uses, so the two suites judge the same user.
List<Map<String, Object?>> habitHistory({int days = 30, bool income = false}) {
  final rows = <Map<String, Object?>>[];
  var id = 1;
  for (var day = 1; day <= days; day++) {
    final moment = caseNow.subtract(Duration(days: day));
    rows.add(_row(id++, 45000 + (day % 5) * 3000, DateTime(moment.year, moment.month, moment.day, 12, 10), 'Ăn uống', !income));
    if (moment.weekday >= DateTime.saturday) {
      rows.add(_row(id++, 280000 + (day % 3) * 20000, DateTime(moment.year, moment.month, moment.day, 17, 30), 'Đi chợ', !income));
    }
  }
  return rows;
}

Map<String, Object?> _row(int id, num amount, DateTime at, String category, bool isExpense) => {
  'id': id,
  'title': category,
  'amount': amount,
  'date': _iso.format(at),
  'category': category,
  'isExpense': isExpense,
};

/// A stand-in for the Big Data API with the answer of the model chosen by the test.
FakeBackend stubFraudBackend({
  required bool anomaly,
  double risk = 0.979,
  int status = 200,
}) {
  final backend = FakeBackend();
  backend.on('POST', '$stubFraudApi/v2/predict-anomaly', (options) {
    if (status != 200) return jsonResponse(status, {'detail': 'boom'});
    return jsonResponse(200, {
      'scored': true,
      'isAnomaly': anomaly,
      'riskScore': risk,
      'threshold': 0.961,
      'explanation': anomaly ? 'Số tiền gấp 11,1 lần mức chi trung bình của bạn.' : '',
      'modelVersion': 'behavior-gbt-2026-10-07',
    });
  });
  return backend;
}

/// The body of the n-th request the app sent to [path] on [backend].
Map<String, dynamic> bodyOf(FakeBackend backend, String method, String path, {int index = 0}) {
  final matches = backend.requests.where((r) => r.method == method && r.path == path).toList();
  return Map<String, dynamic>.from(matches[index].data as Map);
}

DioException connectionError(RequestOptions options) =>
    DioException(requestOptions: options, type: DioExceptionType.connectionError);
