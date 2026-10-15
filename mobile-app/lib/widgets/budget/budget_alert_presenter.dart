import 'dart:async';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'package:finance_ai_app/models/budget_model.dart';
import 'package:finance_ai_app/models/budget_progress.dart';
import 'package:finance_ai_app/services/budget_alert_tracker.dart';
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';

/// Tells the user when a budget reaches 80% or 100% of its limit, in one AppDialog and only once per threshold.
///
/// Every screen that knows about budgets may call [showNew] whenever it (re)loads: the [BudgetAlertTracker] keeps the
/// alerts already shown from coming back, and the calls are queued so that two screens loading at the same moment
/// (the home screen and the ledger live side by side in the tab bar) never open two dialogs.
class BudgetAlertPresenter {
  static const String _warningTitle = 'Sắp chạm hạn mức ngân sách';
  static const String _exceededTitle = 'Đã vượt ngân sách';

  static final NumberFormat _money = NumberFormat.currency(
    locale: 'vi_VN',
    symbol: 'đ',
    decimalDigits: 0,
  );

  /// The last call in the queue; null while nobody has asked yet.
  static Future<void>? _previous;

  /// Shows the alerts of [progresses] that were not shown before, as one dialog: an error when a budget is exceeded,
  /// otherwise a warning. Shows nothing when there is nothing new, or when [context] is gone by then; in that case the
  /// alerts are not marked as shown and stay for the next call. Never throws.
  static Future<void> showNew(
    BuildContext context, {
    required int userId,
    required Iterable<BudgetProgress> progresses,
    BudgetAlertTracker? tracker,
  }) async {
    final snapshot = progresses.toList();
    final before = _previous;
    final finished = Completer<void>();
    _previous = finished.future;
    try {
      if (before != null) await before;
      if (!context.mounted) return;
      await _show(
        context,
        userId,
        snapshot,
        tracker ?? BudgetAlertTracker.instance,
      );
    } catch (e) {
      debugPrint('Lỗi hiển thị cảnh báo ngân sách: $e');
    } finally {
      finished.complete();
    }
  }

  static Future<void> _show(
    BuildContext context,
    int userId,
    List<BudgetProgress> progresses,
    BudgetAlertTracker tracker,
  ) async {
    await tracker.ensureLoaded();
    if (!context.mounted) return;

    final alerts = tracker.pending(userId, progresses);
    if (alerts.isEmpty) return;
    // Marked before the dialog opens, so nothing that runs while it is open can announce the same alerts again.
    final saved = tracker.markShown(userId, alerts);

    alerts.sort((a, b) => b.level.index.compareTo(a.level.index));
    final message = alerts.map(_lineOf).join('\n');
    if (alerts.first.level == BudgetAlertLevel.exceeded) {
      await AppDialog.error(context, title: _exceededTitle, message: message);
    } else {
      await AppDialog.warning(context, title: _warningTitle, message: message);
    }
    await saved;
  }

  static String _lineOf(BudgetAlert alert) {
    final progress = alert.progress;
    final budget = progress.budget;
    final name = budget.categoryName ?? BudgetModel.allCategoriesLabel;
    final period = BudgetPeriodType.label(budget.periodType).toLowerCase();
    final amounts =
        '${_money.format(progress.spent)} / ${_money.format(progress.limit)}';
    return switch (alert.level) {
      BudgetAlertLevel.exceeded =>
        '• $name (theo $period): đã vượt hạn mức — $amounts (${progress.percentLabel}%)',
      _ =>
        '• $name (theo $period): đã dùng ${progress.percentLabel}% hạn mức — $amounts',
    };
  }

  /// Forgets the queue, so a dialog left open by one test cannot hold back the next.
  @visibleForTesting
  static void resetQueue() => _previous = null;
}
