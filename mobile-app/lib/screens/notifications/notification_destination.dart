import 'package:flutter/material.dart';

import 'package:finance_ai_app/models/app_notification.dart';
import 'package:finance_ai_app/screens/budget/budget_screen.dart';

/// Where tapping a notification leads.
///
/// BLOCKER: the app has no transaction detail screen (the Ledger shows a transaction in a bottom sheet of its own, built
/// from the list it already holds) and no screen that reviews a stored receipt (ReviewFormPage only reviews a new scan
/// from a local photo), and core-backend has no GET /api/transactions/{id} or GET /api/receipts/{id} to load one by id.
/// Until those exist, [NotificationType.transactionAlert] and [NotificationType.receiptProcessed] open nothing and the
/// user stays on the notification screen; no placeholder route is made up for them.
abstract final class NotificationDestination {
  /// The page to push for [notification], or null when the app has none for it.
  static WidgetBuilder? of(AppNotification notification) =>
      switch (notification.type) {
        NotificationType.budgetWarning ||
        NotificationType.budgetExceeded => (_) => const BudgetScreen(),
        NotificationType.transactionAlert ||
        NotificationType.receiptProcessed ||
        NotificationType.unknown => null,
      };
}
