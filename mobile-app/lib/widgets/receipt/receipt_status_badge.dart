import 'package:flutter/material.dart';

import 'package:finance_ai_app/core/extensions/build_context_extension.dart';
import 'package:finance_ai_app/models/receipt_status.dart';

/// Small pill telling how far the processing of the receipt attached to a transaction has come. Callers show it only
/// for a transaction that has a status, so a manual transaction stays without a badge.
class ReceiptStatusBadge extends StatelessWidget {
  final ReceiptStatus status;

  const ReceiptStatusBadge({super.key, required this.status});

  Color _colorOf(ThemeData theme) => switch (status) {
    ReceiptStatus.processing => theme.semanticColors.warningColor,
    ReceiptStatus.success => theme.semanticColors.successColor,
    ReceiptStatus.failed => theme.colorScheme.error,
  };

  IconData get _icon => switch (status) {
    ReceiptStatus.processing => Icons.sync_rounded,
    ReceiptStatus.success => Icons.check_circle_rounded,
    ReceiptStatus.failed => Icons.error_rounded,
  };

  @override
  Widget build(BuildContext context) {
    final color = _colorOf(Theme.of(context));
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(_icon, size: 13, color: color),
          const SizedBox(width: 4),
          Flexible(
            child: Text(
              status.label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: color,
                fontSize: 11,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
