import 'package:flutter/material.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/models/budget_progress.dart';
import 'package:finance_ai_app/widgets/budget/budget_progress_card.dart';

/// The "Ngân sách" block shared by the home screen and the ledger: a title, then one [BudgetProgressCard] per budget,
/// or a short note when there is nothing to show or the budgets could not be loaded.
class BudgetSection extends StatelessWidget {
  final List<BudgetProgress> progresses;

  /// The budgets could not be loaded; the rest of the screen is unaffected.
  final bool loadFailed;
  final String emptyText;

  /// Shows only the first [maxItems] budgets and says how many more there are.
  final int? maxItems;
  final double titleSize;

  /// Adds a "Quản lý" link to the title row.
  final VoidCallback? onManage;

  const BudgetSection({
    super.key,
    required this.progresses,
    this.loadFailed = false,
    this.emptyText = 'Chưa có ngân sách trong kỳ này.',
    this.maxItems,
    this.titleSize = 16,
    this.onManage,
  });

  @override
  Widget build(BuildContext context) {
    final limit = maxItems;
    final shown = limit == null ? progresses : progresses.take(limit).toList();
    final hidden = progresses.length - shown.length;
    final colors = Theme.of(context).colorScheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              'Ngân sách',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: titleSize,
              ),
            ),
            if (onManage != null)
              TextButton(onPressed: onManage, child: const Text('Quản lý')),
          ],
        ),
        const SizedBox(height: 12),
        if (loadFailed)
          const _Note('Không tải được ngân sách. Kéo xuống để thử lại.')
        else if (progresses.isEmpty)
          _Note(emptyText)
        else ...[
          for (final progress in shown)
            BudgetProgressCard(
              key: ValueKey(progress.budget.id),
              progress: progress,
            ),
          if (hidden > 0)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(
                '+$hidden ngân sách khác',
                style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
              ),
            ),
        ],
      ],
    );
  }
}

class _Note extends StatelessWidget {
  final String text;

  const _Note(this.text);

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: AppTheme.cardDecoration(colors),
      child: Text(text, style: TextStyle(color: colors.onSurfaceVariant)),
    );
  }
}
