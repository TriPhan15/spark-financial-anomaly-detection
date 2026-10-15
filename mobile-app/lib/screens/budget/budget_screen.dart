import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:shimmer/shimmer.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/constants/transaction_categories.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/core/utils/currency_helper.dart';
import 'package:finance_ai_app/models/budget_model.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/budget/budget_form_sheet.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/widgets/common/primary_button.dart';
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';

const int _httpUnauthorized = 401;
const int _httpForbidden = 403;

/// Lists the spending limits of the user and lets them add, edit and delete them. With the server (AppConfig.isFull)
/// the data lives in the backend; offline it stays in the local database, which only knows monthly budgets.
class BudgetScreen extends StatefulWidget {
  const BudgetScreen({super.key});

  @override
  State<BudgetScreen> createState() => _BudgetScreenState();
}

class _BudgetScreenState extends State<BudgetScreen> {
  List<BudgetModel> _budgets = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _load();
    });
  }

  static String _messageOf(Object error) {
    if (error is DioException) {
      final status = error.response?.statusCode;
      if (status == _httpUnauthorized || status == _httpForbidden) {
        return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
      }
      final data = error.response?.data;
      final serverMessage = (data is Map ? data['message'] : null)?.toString();
      return serverMessage ?? 'Không thể kết nối máy chủ. Vui lòng thử lại.';
    }
    return 'Đã xảy ra lỗi. Vui lòng thử lại.';
  }

  Future<void> _load() async {
    final userId = SessionService.currentUserId;
    if (userId == null) {
      setState(() => _isLoading = false);
      await _showSessionExpired();
      return;
    }
    final List<BudgetModel> budgets;
    try {
      budgets = await AppRepository.instance.getBudgets(userId);
    } catch (e) {
      debugPrint('Lỗi tải ngân sách: $e');
      if (!mounted) return;
      setState(() => _isLoading = false);
      await AppDialog.error(
        context,
        title: 'Không tải được ngân sách',
        message: _messageOf(e),
      );
      return;
    }
    if (!mounted) return;
    setState(() {
      _budgets = budgets;
      _isLoading = false;
    });
  }

  Future<void> _showSessionExpired() {
    return AppDialog.error(
      context,
      title: 'Phiên đăng nhập hết hạn',
      message: 'Vui lòng đăng nhập lại.',
    );
  }

  Future<void> _openForm([BudgetModel? budget]) async {
    final userId = SessionService.currentUserId;
    if (userId == null) {
      await _showSessionExpired();
      return;
    }
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => BudgetFormSheet(
        budget: budget,
        weeklyEnabled: AppConfig.isFull,
        loadCategories: () =>
            AppRepository.instance.getExpenseCategories(userId),
        onSubmit: (edited) => _submit(userId, edited),
      ),
    );
    if (saved != true || !mounted) return;
    await _load();
  }

  Future<String?> _submit(int userId, BudgetModel budget) async {
    try {
      await AppRepository.instance.saveBudget(userId, budget);
      return null;
    } catch (e) {
      debugPrint('Lỗi lưu ngân sách: $e');
      return _messageOf(e);
    }
  }

  Future<void> _delete(BudgetModel budget) async {
    final budgetId = budget.id;
    if (budgetId == null) return;
    final confirmed = await AppDialog.confirm(
      context,
      title: 'Xóa ngân sách?',
      message:
          'Ngân sách "${budget.categoryName ?? BudgetModel.allCategoriesLabel}" sẽ bị xóa.',
      confirmText: 'Xóa',
      danger: true,
    );
    if (!confirmed || !mounted) return;

    try {
      await AppRepository.instance.deleteBudget(budgetId);
    } catch (e) {
      debugPrint('Lỗi xóa ngân sách: $e');
      if (!mounted) return;
      await AppDialog.error(
        context,
        title: 'Không xóa được ngân sách',
        message: _messageOf(e),
      );
      return;
    }
    if (!mounted) return;
    setState(() => _budgets = _budgets.where((b) => b.id != budgetId).toList());
    await AppDialog.success(context, title: 'Đã xóa ngân sách');
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: colors.surface,
      // Transparent so that it shows the page colour; icons and title take the app bar theme.
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        title: const Text(
          'Ngân sách',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 20),
        ),
        actions: [
          IconButton(
            icon: Icon(Icons.add_rounded, color: colors.primary),
            onPressed: () => _openForm(),
          ),
        ],
      ),
      body: _isLoading
          ? const _BudgetSkeleton()
          : RefreshIndicator(
              onRefresh: _load,
              child: ListView(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
                children: [
                  if (!AppConfig.isFull) const _OfflineNotice(),
                  if (_budgets.isEmpty)
                    _EmptyState(onCreate: _openForm)
                  else
                    for (final budget in _budgets)
                      _BudgetCard(
                        budget: budget,
                        onEdit: () => _openForm(budget),
                        onDelete: () => _delete(budget),
                      ),
                ],
              ),
            ),
    );
  }
}

class _OfflineNotice extends StatelessWidget {
  const _OfflineNotice();

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: colors.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        'Chế độ ngoại tuyến: chỉ hỗ trợ ngân sách theo tháng.',
        style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  final VoidCallback onCreate;

  const _EmptyState({required this.onCreate});

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(32),
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        children: [
          Icon(
            Icons.pie_chart_outline_rounded,
            color: colors.onSurfaceVariant,
            size: 48,
          ),
          const SizedBox(height: 12),
          Text(
            'Chưa có ngân sách nào.',
            style: TextStyle(color: colors.onSurfaceVariant),
          ),
          const SizedBox(height: 4),
          Text(
            'Đặt hạn mức chi tiêu theo tuần hoặc tháng để kiểm soát tốt hơn.',
            textAlign: TextAlign.center,
            style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
          ),
          const SizedBox(height: 20),
          PrimaryButton(text: 'Thiết lập ngân sách', onPressed: onCreate),
        ],
      ),
    );
  }
}

class _BudgetCard extends StatelessWidget {
  static final DateFormat _displayDate = DateFormat('dd/MM/yyyy');

  final BudgetModel budget;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  const _BudgetCard({
    required this.budget,
    required this.onEdit,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final categoryName = budget.categoryName;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: InkWell(
        onTap: onEdit,
        borderRadius: BorderRadius.circular(20),
        child: Container(
          padding: const EdgeInsets.fromLTRB(16, 16, 8, 16),
          decoration: AppTheme.cardDecoration(colors),
          child: Row(
            children: [
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: colors.primaryContainer,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Icon(
                  categoryName == null
                      ? Icons.pie_chart_rounded
                      : TransactionCategories.iconFor(categoryName),
                  color: colors.onPrimaryContainer,
                  size: 22,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      categoryName ?? BudgetModel.allCategoriesLabel,
                      style: const TextStyle(fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${BudgetPeriodType.label(budget.periodType)} · '
                      '${_displayDate.format(budget.start)} – ${_displayDate.format(budget.end)}',
                      style: TextStyle(
                        color: colors.onSurfaceVariant,
                        fontSize: 11,
                      ),
                    ),
                  ],
                ),
              ),
              Text(
                CurrencyHelper.formatVND(budget.amount, showSign: false),
                style: TextStyle(
                  color: colors.primary,
                  fontWeight: FontWeight.bold,
                  fontSize: 14,
                ),
              ),
              IconButton(
                icon: Icon(
                  Icons.delete_outline_rounded,
                  color: colors.error,
                  size: 20,
                ),
                onPressed: onDelete,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Three card-shaped placeholders that shimmer from the container colour of the theme to its surface.
class _BudgetSkeleton extends StatelessWidget {
  const _BudgetSkeleton();

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    // The shimmer paints over the boxes, so their own colour only has to be opaque.
    final box = BoxDecoration(
      color: colors.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(20),
    );
    return Shimmer.fromColors(
      baseColor: colors.surfaceContainerHighest,
      highlightColor: colors.surface,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
        physics: const NeverScrollableScrollPhysics(),
        children: List.generate(
          3,
          (_) => Container(
            height: 76,
            margin: const EdgeInsets.only(bottom: 12),
            decoration: box,
          ),
        ),
      ),
    );
  }
}
