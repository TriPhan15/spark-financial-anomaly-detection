import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/models/budget_progress.dart';

/// One threshold of one budget that the user has to be told about.
class BudgetAlert {
  final BudgetProgress progress;
  final BudgetAlertLevel level;

  const BudgetAlert(this.progress, this.level);
}

/// Remembers which budget alerts were already shown, so each threshold is announced once per budget and period, however
/// often a screen reloads, rebuilds or changes tab. The memory lives in this object and is mirrored to
/// SharedPreferences, so it survives a restart of the app too.
///
/// A flag is made of the user, the budget, its period, its limit and the level. A new period is therefore a new
/// budget, and so is a changed limit: both may alert again, while a reload of the same budget never does.
class BudgetAlertTracker {
  static final BudgetAlertTracker instance = BudgetAlertTracker();

  static const String _prefsKey = 'budget_alerts_shown';
  static const String _separator = '|';
  static const int _endDateIndex = 3;

  /// Flags of periods that ended longer ago than this are forgotten.
  static const Duration _keepFlagsFor = Duration(days: 30);

  final Set<String> _shown = {};
  Future<void>? _loading;

  Future<void> ensureLoaded() => _loading ??= _load();

  Future<void> _load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      _shown.addAll(prefs.getStringList(_prefsKey) ?? const <String>[]);
    } catch (e) {
      debugPrint('Lỗi đọc trạng thái cảnh báo ngân sách: $e');
    }
  }

  /// The alerts of [progresses] the user has not seen yet: at most one per budget, for the highest level it has reached.
  /// Call [ensureLoaded] first.
  List<BudgetAlert> pending(int userId, Iterable<BudgetProgress> progresses) {
    final alerts = <BudgetAlert>[];
    for (final progress in progresses) {
      final level = progress.level;
      if (level == BudgetAlertLevel.none) continue;
      if (_shown.contains(_flag(userId, progress, level))) continue;
      alerts.add(BudgetAlert(progress, level));
    }
    return alerts;
  }

  /// Records [alerts] as shown. The in-memory flags are set before the first await, so a check made right after
  /// this call already sees them. Reaching "exceeded" also marks "warning", so a jump from 70% straight to 110%
  /// never alerts a second time for the lower level.
  Future<void> markShown(
    int userId,
    Iterable<BudgetAlert> alerts, {
    DateTime? now,
  }) async {
    for (final alert in alerts) {
      _shown.add(_flag(userId, alert.progress, BudgetAlertLevel.warning));
      if (alert.level == BudgetAlertLevel.exceeded) {
        _shown.add(_flag(userId, alert.progress, BudgetAlertLevel.exceeded));
      }
    }
    _forgetOldFlags(now ?? DateTime.now());

    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setStringList(_prefsKey, _shown.toList());
    } catch (e) {
      debugPrint('Lỗi lưu trạng thái cảnh báo ngân sách: $e');
    }
  }

  /// Forgets everything held in memory; the next [ensureLoaded] reads SharedPreferences again.
  @visibleForTesting
  void resetMemory() {
    _shown.clear();
    _loading = null;
  }

  String _flag(int userId, BudgetProgress progress, BudgetAlertLevel level) {
    final budget = progress.budget;
    return [
      userId,
      budget.id,
      budget.startDate,
      budget.endDate,
      budget.amount,
      level.name,
    ].join(_separator);
  }

  void _forgetOldFlags(DateTime now) {
    _shown.removeWhere((flag) {
      final parts = flag.split(_separator);
      if (parts.length <= _endDateIndex) return true;
      final end = DateTime.tryParse(parts[_endDateIndex]);
      return end == null || end.add(_keepFlagsFor).isBefore(now);
    });
  }
}
