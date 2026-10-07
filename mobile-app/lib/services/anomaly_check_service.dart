import 'package:dio/dio.dart';
import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:flutter/foundation.dart';
import 'package:intl/intl.dart';

class AnomalyResult {
  final bool isAnomaly;
  final double riskScore;
  final double threshold;
  final String explanation;
  final String modelVersion;

  const AnomalyResult({
    required this.isAnomaly,
    required this.riskScore,
    required this.threshold,
    required this.explanation,
    required this.modelVersion,
  });

  static AnomalyResult? fromJson(Map<String, dynamic> json) {
    final isAnomaly = json['isAnomaly'];
    final riskScore = json['riskScore'];
    final threshold = json['threshold'];
    if (json['scored'] != true ||
        isAnomaly is! bool ||
        riskScore is! num ||
        threshold is! num) {
      return null;
    }
    final explanation = json['explanation'];
    final modelVersion = json['modelVersion'];
    return AnomalyResult(
      isAnomaly: isAnomaly,
      riskScore: riskScore.toDouble(),
      threshold: threshold.toDouble(),
      explanation: explanation is String ? explanation : '',
      modelVersion: modelVersion is String ? modelVersion : '',
    );
  }
}

/// Asks the behavior-anomaly API whether a new expense is unusual for the user.
///
/// The app only sends the current transaction and the user's recent expense
/// history. The server computes the behavior features (amount ratio vs the
/// user's averages, 24h count, time since previous, hour/day, category) with
/// the same formulas as the Spark training job; the app never fabricates
/// features. Fail-open: any problem returns null so saving is never blocked.
class AnomalyCheckService {
  static const String _baseUrl = String.fromEnvironment('FRAUD_API_URL');
  static const int _maxHistory = 100;

  static final Dio _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 3),
      sendTimeout: const Duration(seconds: 3),
      receiveTimeout: const Duration(seconds: 3),
      contentType: Headers.jsonContentType,
    ),
  );

  static final DateFormat _isoFormat = DateFormat("yyyy-MM-dd'T'HH:mm:ss");

  static Future<AnomalyResult?> check({
    required double amount,
    required DateTime occurredAt,
    required String category,
  }) async {
    if (!AppConfig.isFull || _baseUrl.isEmpty) return null;

    try {
      final transactions = await RemoteApiHelper.instance.fetchTransactions();
      final expenses = transactions
          .where((t) => t.isExpense && t.amount > 0)
          .toList()
        ..sort((a, b) => b.date.compareTo(a.date));

      final base = _baseUrl.endsWith('/')
          ? _baseUrl.substring(0, _baseUrl.length - 1)
          : _baseUrl;

      final response = await _dio.post<Map<String, dynamic>>(
        '$base/v2/predict-anomaly',
        data: {
          'current': {
            'amount': amount,
            'occurredAt': _isoFormat.format(occurredAt),
            'category': category,
          },
          'history': [
            for (final t in expenses.take(_maxHistory))
              {
                'amount': t.amount,
                'occurredAt': _isoFormat.format(t.date),
                'category': t.category,
              },
          ],
        },
      );

      final data = response.data;
      if (data == null) return null;
      return AnomalyResult.fromJson(data);
    } catch (error) {
      // Log only the error type: never amounts or receipt data.
      debugPrint('Anomaly check unavailable: ${error.runtimeType}');
      return null;
    }
  }
}
