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

class FlaggedTransaction {
  final String? id;
  final double amount;
  final DateTime? occurredAt;
  final String category;
  final double riskScore;
  final String explanation;

  const FlaggedTransaction({
    this.id,
    required this.amount,
    required this.occurredAt,
    required this.category,
    required this.riskScore,
    required this.explanation,
  });
}

/// Result of POST /v2/scan-history: the expenses the model flags as unusual for the user.
class AnomalyScan {
  final String modelVersion;
  final int scannedCount;
  final int scoredCount;
  final int flaggedCount;
  final Map<String, int> flaggedByCategory;
  final List<FlaggedTransaction> flagged;

  const AnomalyScan({
    required this.modelVersion,
    required this.scannedCount,
    required this.scoredCount,
    required this.flaggedCount,
    required this.flaggedByCategory,
    required this.flagged,
  });

  static AnomalyScan? fromJson(Map<String, dynamic> json) {
    final scanned = json['scannedCount'];
    final scored = json['scoredCount'];
    final flaggedCount = json['flaggedCount'];
    final byCategory = json['flaggedByCategory'];
    final flagged = json['flagged'];
    if (scanned is! int ||
        scored is! int ||
        flaggedCount is! int ||
        byCategory is! Map ||
        flagged is! List) {
      return null;
    }
    final version = json['modelVersion'];
    return AnomalyScan(
      modelVersion: version is String ? version : '',
      scannedCount: scanned,
      scoredCount: scored,
      flaggedCount: flaggedCount,
      flaggedByCategory: {
        for (final e in byCategory.entries)
          if (e.value is num) e.key.toString(): (e.value as num).toInt(),
      },
      flagged: [
        for (final item in flagged)
          if (item is Map && item['riskScore'] is num && item['amount'] is num)
            FlaggedTransaction(
              id: item['id']?.toString(),
              amount: (item['amount'] as num).toDouble(),
              occurredAt: DateTime.tryParse('${item['occurredAt']}'),
              category: '${item['category']}',
              riskScore: (item['riskScore'] as num).toDouble(),
              explanation: item['explanation'] is String
                  ? item['explanation'] as String
                  : '',
            ),
      ],
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

  static const int _maxScan = 1000;
  static final Dio _scanDio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 10),
      sendTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 10),
      contentType: Headers.jsonContentType,
    ),
  );

  static String? _cacheKey;
  static AnomalyScan? _cachedScan;

  /// Drops the session cache; called after a new transaction is saved.
  static void clearScanCache() {
    _cacheKey = null;
    _cachedScan = null;
  }

  /// Scans the user's whole expense history. Cached for the session under the
  /// key (transaction count, newest date); [forceRefresh] bypasses the cache.
  /// Returns null when not in full mode, no URL is configured, or on any error.
  static Future<AnomalyScan?> scanHistory({bool forceRefresh = false}) async {
    if (!AppConfig.isFull || _baseUrl.isEmpty) return null;

    try {
      final transactions = await RemoteApiHelper.instance.fetchTransactions();
      final expenses = transactions
          .where((t) => t.isExpense && t.amount > 0)
          .toList()
        ..sort((a, b) => b.date.compareTo(a.date));
      final recent = expenses.take(_maxScan).toList();
      final key = '${recent.length}|${recent.isEmpty ? '' : recent.first.date.toIso8601String()}';
      if (!forceRefresh && _cachedScan != null && _cacheKey == key) {
        return _cachedScan;
      }

      final base = _baseUrl.endsWith('/')
          ? _baseUrl.substring(0, _baseUrl.length - 1)
          : _baseUrl;
      final response = await _scanDio.post<Map<String, dynamic>>(
        '$base/v2/scan-history',
        data: {
          'transactions': [
            for (final t in recent)
              {
                if (t.id != null) 'id': t.id,
                'amount': t.amount,
                'occurredAt': _isoFormat.format(t.date),
                'category': t.category,
              },
          ],
        },
      );
      final data = response.data;
      if (data == null) return null;
      final scan = AnomalyScan.fromJson(data);
      if (scan != null) {
        _cacheKey = key;
        _cachedScan = scan;
      }
      return scan;
    } catch (error) {
      debugPrint('Anomaly scan unavailable: ${error.runtimeType}');
      return null;
    }
  }

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
