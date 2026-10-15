import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'package:finance_ai_app/models/budget_model.dart';
import 'package:finance_ai_app/widgets/common/custom_text_field.dart';
import 'package:finance_ai_app/widgets/common/primary_button.dart';
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';

/// Bottom sheet to create a budget or edit one. It only collects and checks the input: the screen that opens it
/// sends the request and reports a failure by returning its message from [onSubmit]. Pops with true once saved.
class BudgetFormSheet extends StatefulWidget {
  /// The budget being edited; null when creating a new one.
  final BudgetModel? budget;

  /// Weekly budgets need the server; the local table only knows months.
  final bool weeklyEnabled;

  final Future<List<BudgetCategory>> Function() loadCategories;

  /// Saves the budget. Returns null on success, or the message to show when it failed.
  final Future<String?> Function(BudgetModel budget) onSubmit;

  const BudgetFormSheet({
    super.key,
    this.budget,
    required this.weeklyEnabled,
    required this.loadCategories,
    required this.onSubmit,
  });

  @override
  State<BudgetFormSheet> createState() => _BudgetFormSheetState();
}

class _BudgetFormSheetState extends State<BudgetFormSheet> {
  final _amountController = TextEditingController();
  late String _periodType;

  /// Null means the budget covers every category.
  BudgetCategory? _category;
  List<BudgetCategory> _categories = [];
  bool _isLoadingCategories = true;
  bool _isSaving = false;

  bool get _isEditing => widget.budget != null;

  /// An edited budget keeps its dates while its period type is unchanged; otherwise the current week or month.
  bool get _keepsDates => widget.budget?.periodType == _periodType;

  ({DateTime start, DateTime end}) get _range {
    final existing = widget.budget;
    if (existing != null && _keepsDates) {
      return (start: existing.start, end: existing.end);
    }
    return BudgetPeriodType.rangeContaining(_periodType, DateTime.now());
  }

  @override
  void initState() {
    super.initState();
    final existing = widget.budget;
    _periodType = existing?.periodType ?? BudgetPeriodType.month;
    if (existing != null) {
      _amountController.text = existing.amount.toStringAsFixed(0);
      final categoryId = existing.categoryId;
      if (categoryId != null) {
        _category = (id: categoryId, name: existing.categoryName ?? '');
      }
    }
    _loadCategories();
  }

  @override
  void dispose() {
    _amountController.dispose();
    super.dispose();
  }

  Future<void> _loadCategories() async {
    final List<BudgetCategory> categories;
    try {
      categories = await widget.loadCategories();
    } catch (e) {
      debugPrint('Lỗi tải danh mục cho ngân sách: $e');
      if (!mounted) return;
      setState(() => _isLoadingCategories = false);
      await AppDialog.error(
        context,
        title: 'Không tải được danh mục',
        message:
            'Bạn vẫn có thể chọn "${BudgetModel.allCategoriesLabel}" hoặc thử lại sau.',
      );
      return;
    }
    if (!mounted) return;
    setState(() {
      _categories = _withSelected(categories);
      _isLoadingCategories = false;
    });
  }

  /// Keeps the category of the edited budget selectable even when the loaded list does not contain it.
  List<BudgetCategory> _withSelected(List<BudgetCategory> loaded) {
    final selected = _category;
    if (selected == null ||
        loaded.any((category) => category.id == selected.id)) {
      return loaded;
    }
    return [selected, ...loaded];
  }

  double? _parseAmount(String input) {
    final digitsOnly = input.replaceAll(RegExp(r'[^0-9]'), '');
    if (digitsOnly.isEmpty) return null;
    final value = double.tryParse(digitsOnly);
    if (value == null || value <= 0) return null;
    return value;
  }

  BudgetModel _buildBudget(double amount) {
    final range = _range;
    return BudgetModel(
      id: widget.budget?.id,
      categoryId: _category?.id,
      categoryName: _category?.name,
      amount: amount,
      periodType: _periodType,
      startDate: BudgetModel.formatApiDate(range.start),
      endDate: BudgetModel.formatApiDate(range.end),
      userId: widget.budget?.userId,
    );
  }

  Future<void> _save() async {
    final amount = _parseAmount(_amountController.text);
    if (amount == null) {
      await AppDialog.error(
        context,
        title: 'Số tiền không hợp lệ',
        message: 'Vui lòng nhập số tiền ngân sách lớn hơn 0.',
      );
      return;
    }

    setState(() => _isSaving = true);
    final error = await widget.onSubmit(_buildBudget(amount));
    if (!mounted) return;
    setState(() => _isSaving = false);

    if (error != null) {
      await AppDialog.error(
        context,
        title: 'Không lưu được ngân sách',
        message: error,
      );
      return;
    }
    await AppDialog.success(
      context,
      title: 'Đã lưu ngân sách',
      message: 'Hạn mức chi tiêu của bạn đã được cập nhật.',
    );
    if (!mounted) return;
    Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _isEditing ? 'Sửa ngân sách' : 'Thiết lập ngân sách',
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 16),
            CustomTextField(
              controller: _amountController,
              label: 'Số tiền ngân sách (₫)',
              keyboardType: TextInputType.number,
            ),
            const SizedBox(height: 20),
            const _SectionLabel('Kỳ hạn'),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: [
                for (final type in BudgetPeriodType.values)
                  _OptionChip(
                    label: BudgetPeriodType.label(type),
                    selected: _periodType == type,
                    enabled:
                        widget.weeklyEnabled || type != BudgetPeriodType.week,
                    onSelected: () => setState(() => _periodType = type),
                  ),
              ],
            ),
            if (!widget.weeklyEnabled)
              const _Hint('Ngân sách theo tuần cần kết nối máy chủ.'),
            const SizedBox(height: 12),
            _PeriodPreview(
              label: _keepsDates && _isEditing
                  ? 'Kỳ áp dụng'
                  : _periodType == BudgetPeriodType.week
                  ? 'Tuần này'
                  : 'Tháng này',
              range: _range,
            ),
            const SizedBox(height: 20),
            const _SectionLabel('Danh mục'),
            const SizedBox(height: 8),
            _buildCategories(),
            const SizedBox(height: 24),
            PrimaryButton(
              text: _isSaving ? 'Đang lưu...' : 'Lưu ngân sách',
              onPressed: _isSaving ? null : _save,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCategories() {
    if (_isLoadingCategories) {
      return const SizedBox(
        height: 40,
        child: Align(
          alignment: Alignment.centerLeft,
          child: SizedBox(
            width: 20,
            height: 20,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        ),
      );
    }
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        _OptionChip(
          label: BudgetModel.allCategoriesLabel,
          selected: _category == null,
          onSelected: () => setState(() => _category = null),
        ),
        for (final category in _categories)
          _OptionChip(
            label: category.name,
            selected: _category?.id == category.id,
            onSelected: () => setState(() => _category = category),
          ),
      ],
    );
  }
}

class _SectionLabel extends StatelessWidget {
  final String text;

  const _SectionLabel(this.text);

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: TextStyle(
        color: Theme.of(context).colorScheme.onSurfaceVariant,
        fontSize: 14,
      ),
    );
  }
}

class _Hint extends StatelessWidget {
  final String text;

  const _Hint(this.text);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: Text(
        text,
        style: TextStyle(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          fontSize: 12,
        ),
      ),
    );
  }
}

class _OptionChip extends StatelessWidget {
  final String label;
  final bool selected;
  final bool enabled;
  final VoidCallback onSelected;

  const _OptionChip({
    required this.label,
    required this.selected,
    required this.onSelected,
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return ChoiceChip(
      label: Text(label),
      selected: selected,
      showCheckmark: false,
      onSelected: enabled ? (_) => onSelected() : null,
      selectedColor: colors.primary,
      backgroundColor: colors.surface,
      side: BorderSide(color: colors.outlineVariant),
      labelStyle: TextStyle(
        color: selected ? colors.onPrimary : colors.onSurface,
      ),
    );
  }
}

class _PeriodPreview extends StatelessWidget {
  static final DateFormat _displayDate = DateFormat('dd/MM/yyyy');

  final String label;
  final ({DateTime start, DateTime end}) range;

  const _PeriodPreview({required this.label, required this.range});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(
          Icons.event_rounded,
          color: Theme.of(context).colorScheme.primary,
          size: 18,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            '$label: ${_displayDate.format(range.start)} – ${_displayDate.format(range.end)}',
            style: const TextStyle(fontSize: 13),
          ),
        ),
      ],
    );
  }
}
