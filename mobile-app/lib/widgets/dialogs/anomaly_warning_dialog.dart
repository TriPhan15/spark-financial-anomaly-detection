import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'package:finance_ai_app/services/anomaly_check_service.dart';

/// The warning shown before an expense that the behavior model flags is saved. Every way of entering an expense (receipt
/// scan, manual form, edit form) asks the same question through [AnomalyWarningDialog.confirm], so the wording and the
/// two choices stay identical.
class AnomalyWarningDialog {
  AnomalyWarningDialog._();

  /// True when the user chooses to save anyway, false on Cancel.
  static Future<bool> confirm(
    BuildContext context, {
    required AnomalyResult result,
    required double amount,
  }) async {
    final colors = Theme.of(context).colorScheme;
    final formatter = NumberFormat.currency(
      locale: 'vi_VN',
      symbol: 'đ',
      decimalDigits: 0,
    );
    final risk = (result.riskScore * 100).toStringAsFixed(1);
    final threshold = (result.threshold * 100).toStringAsFixed(1);
    final shouldSave = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        icon: Icon(Icons.warning_amber_rounded, color: colors.error, size: 32),
        title: Text(
          'Chi tiêu bất thường so với thói quen',
          style: TextStyle(color: colors.error),
        ),
        content: Text(
          '${formatter.format(amount)}\n'
          '${result.explanation.isNotEmpty ? '${result.explanation}\n' : ''}'
          '${result.flaggedByRule ? 'Khoản này vượt xa mức bạn thường chi nên cần bạn xác nhận.' : 'Điểm rủi ro: $risk% (ngưỡng cảnh báo $threshold%)'}\n\n'
          'Bạn có muốn tiếp tục lưu không?',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Hủy'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: colors.error,
              foregroundColor: colors.onError,
            ),
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Vẫn lưu'),
          ),
        ],
      ),
    );
    return shouldSave ?? false;
  }
}
