import 'package:flutter/material.dart';

import 'package:finance_ai_app/core/constants/transaction_categories.dart';
import 'package:finance_ai_app/core/extensions/build_context_extension.dart';
import 'package:finance_ai_app/models/sync_status.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';

const String _saveFailedMessage = 'Không lưu được giao dịch. Vui lòng thử lại.';
const String _notSentTitle = 'Chưa gửi được lên máy chủ';
const String _notSentMessage =
    'Giao dịch đã được lưu trên thiết bị nhưng chưa gửi được lên máy chủ. '
    'Khi có mạng, mở Sổ sách, chọn giao dịch và bấm "Gửi lại". Gửi lại sẽ không tạo giao dịch trùng.';

class AddTransactionPage extends StatefulWidget {
  const AddTransactionPage({super.key, this.initialDate});

  final DateTime? initialDate;

  @override
  State<AddTransactionPage> createState() => _AddTransactionPageState();
}

class _AddTransactionPageState extends State<AddTransactionPage> {
  bool _isExpense = true;
  final TextEditingController _amountController = TextEditingController();
  final TextEditingController _noteController = TextEditingController();
  String _selectedCategory = 'Khác';
  late DateTime _selectedDate;
  bool _isSaving = false;

  @override
  void initState() {
    super.initState();

    _initCategories();

    final initial = widget.initialDate;
    if (initial == null) {
      _selectedDate = DateTime.now();
    } else {
      final now = DateTime.now();
      _selectedDate = DateTime(
        initial.year,
        initial.month,
        initial.day,
        now.hour,
        now.minute,
        now.second,
      );
    }
  }

  Future<void> _initCategories() async {
    if (TransactionCategories.expenseSelectable.isEmpty) {
      await TransactionCategories.loadFromApi();
    }
    if (mounted && TransactionCategories.expenseSelectable.isNotEmpty) {
      setState(() {
        _selectedCategory = TransactionCategories.expenseSelectable.first;
      });
    }
  }

  String _formatDate(DateTime date) {
    final dd = date.day.toString().padLeft(2, '0');
    final mm = date.month.toString().padLeft(2, '0');
    final yyyy = date.year.toString();
    return '$dd/$mm/$yyyy';
  }

  void _setIsExpense(bool isExpense) {
    if (_isExpense == isExpense) return;
    setState(() {
      _isExpense = isExpense;
      final categories = TransactionCategories.forType(isExpense: _isExpense);
      if (!categories.contains(_selectedCategory)) {
        _selectedCategory = categories.isNotEmpty ? categories.first : 'Khác';
      }
    });
  }

  Future<void> _pickCategory() async {
    if (TransactionCategories.forType(isExpense: _isExpense).isEmpty) {
      await TransactionCategories.loadFromApi();
    }

    if (!mounted) return;
    final categories = TransactionCategories.forType(isExpense: _isExpense);

    final picked = await showModalBottomSheet<String>(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (context) {
        final colors = Theme.of(context).colorScheme;
        return SafeArea(
          child: ListView(
            shrinkWrap: true,
            padding: const EdgeInsets.symmetric(vertical: 8),
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
                child: Text(
                  _isExpense ? 'Chọn danh mục chi' : 'Chọn danh mục thu',
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
              ...categories.map((category) {
                final selected = category == _selectedCategory;
                return ListTile(
                  leading: Icon(
                    TransactionCategories.iconFor(category),
                    color: selected ? colors.primary : colors.onSurfaceVariant,
                  ),
                  title: Text(
                    category,
                    style: TextStyle(
                      color: selected
                          ? colors.onSurface
                          : colors.onSurfaceVariant,
                      fontWeight: selected
                          ? FontWeight.w700
                          : FontWeight.normal,
                    ),
                  ),
                  trailing: selected
                      ? Icon(Icons.check_rounded, color: colors.primary)
                      : null,
                  onTap: () => Navigator.pop(context, category),
                );
              }),
            ],
          ),
        );
      },
    );

    if (!mounted || picked == null) return;
    setState(() {
      _selectedCategory = picked;
    });
  }

  Future<void> _pickDate() async {
    final initialDate = DateTime(
      _selectedDate.year,
      _selectedDate.month,
      _selectedDate.day,
    );

    final picked = await showDatePicker(
      context: context,
      initialDate: initialDate,
      firstDate: DateTime(2000),
      lastDate: DateTime(2100),
    );

    if (!mounted || picked == null) return;

    setState(() {
      _selectedDate = DateTime(
        picked.year,
        picked.month,
        picked.day,
        _selectedDate.hour,
        _selectedDate.minute,
        _selectedDate.second,
      );
    });
  }

  double? _parseAmount(String input) {
    final digitsOnly = input.replaceAll(RegExp(r'[^0-9]'), '');
    if (digitsOnly.isEmpty) return null;
    final value = double.tryParse(digitsOnly);
    if (value == null || value <= 0) return null;
    return value;
  }

  Future<void> _saveTransaction() async {
    if (_isSaving) return;
    final userId = SessionService.currentUserId;
    if (userId == null) {
      if (!mounted) return;
      await AppDialog.error(
        context,
        title: 'Phiên đăng nhập hết hạn',
        message: 'Vui lòng đăng nhập lại.',
      );
      return;
    }

    final amount = _parseAmount(_amountController.text.trim());
    if (amount == null) {
      if (!mounted) return;
      await AppDialog.error(
        context,
        title: 'Số tiền không hợp lệ',
        message: 'Vui lòng nhập số tiền hợp lệ.',
      );
      return;
    }

    final title = _noteController.text.trim().isEmpty
        ? _selectedCategory
        : _noteController.text.trim();

    final transaction = TransactionModel.draft(
      title: title,
      category: _selectedCategory,
      amount: amount,
      date: _selectedDate,
      isExpense: _isExpense,
    );

    setState(() => _isSaving = true);
    SyncStatus? status;
    String? failure;
    try {
      status = await AppRepository.instance.createTransaction(
        userId,
        transaction,
      );
    } on TransactionRefusedException catch (e) {
      failure = e.message;
    } catch (e) {
      debugPrint('Lỗi lưu giao dịch: $e');
      failure = _saveFailedMessage;
    }
    if (!mounted) return;
    setState(() => _isSaving = false);

    if (failure != null) {
      await AppDialog.error(context, title: 'Lưu thất bại', message: failure);
      return;
    }
    // The transaction is on the device in either case; when the server does not have it yet, the Ledger shows it flagged
    // and offers to send it again, so the form closes instead of inviting a second, different save of the same thing.
    if (status == SyncStatus.synced) {
      await AppDialog.success(
        context,
        title: 'Thành công',
        message: 'Đã thêm giao dịch mới.',
      );
    } else {
      await AppDialog.warning(
        context,
        title: _notSentTitle,
        message: _notSentMessage,
      );
    }
    if (!mounted) return;
    Navigator.pop(context, true);
  }

  @override
  void dispose() {
    _amountController.dispose();
    _noteController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final semantic = theme.semanticColors;
    final labelStyle = TextStyle(color: colors.onSurfaceVariant, fontSize: 14);
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Thêm giao dịch',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        leading: IconButton(
          icon: const Icon(Icons.close_rounded),
          onPressed: () => Navigator.pop(context),
        ),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildTypeToggle(colors),
            const SizedBox(height: 32),
            Text('Số tiền', style: labelStyle),
            TextField(
              controller: _amountController,
              keyboardType: TextInputType.number,
              style: TextStyle(
                color: _isExpense ? semantic.expenseColor : colors.primary,
                fontSize: 40,
                fontWeight: FontWeight.bold,
              ),
              decoration: InputDecoration(
                hintText: '0',
                hintStyle: TextStyle(
                  color: colors.onSurfaceVariant.withValues(alpha: 0.3),
                  fontSize: 40,
                ),
                suffixText: 'đ',
                suffixStyle: TextStyle(color: colors.onSurface, fontSize: 24),
                border: InputBorder.none,
              ),
            ),
            Divider(color: colors.onSurfaceVariant.withValues(alpha: 0.3)),
            const SizedBox(height: 24),
            Text('Danh mục', style: labelStyle),
            const SizedBox(height: 8),
            InkWell(
              onTap: _pickCategory,
              child: Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: colors.surface,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: colors.primary.withValues(alpha: 0.15),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Icon(
                        TransactionCategories.iconFor(_selectedCategory),
                        color: colors.primary,
                      ),
                    ),
                    const SizedBox(width: 16),
                    Expanded(
                      child: Text(
                        _selectedCategory,
                        style: const TextStyle(fontSize: 16),
                      ),
                    ),
                    Icon(
                      Icons.chevron_right_rounded,
                      color: colors.onSurfaceVariant,
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Ngày giao dịch', style: labelStyle),
                      const SizedBox(height: 8),
                      InkWell(
                        onTap: _pickDate,
                        borderRadius: BorderRadius.circular(16),
                        child: Container(
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: colors.surface,
                            borderRadius: BorderRadius.circular(16),
                          ),
                          child: Row(
                            children: [
                              Icon(
                                Icons.calendar_today_rounded,
                                color: colors.onSurfaceVariant,
                                size: 20,
                              ),
                              const SizedBox(width: 12),
                              Text(_formatDate(_selectedDate)),
                              const Spacer(),
                              Icon(
                                Icons.chevron_right_rounded,
                                color: colors.onSurfaceVariant,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 24),
            Text('Ghi chú (Tùy chọn)', style: labelStyle),
            const SizedBox(height: 8),
            // Text, hint and fill come from the theme: onSurface, onSurfaceVariant and the surface colour.
            TextField(
              controller: _noteController,
              decoration: InputDecoration(
                hintText: 'VD: Mua cafe Highlands',
                filled: true,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(16),
                  borderSide: BorderSide.none,
                ),
              ),
            ),
            const SizedBox(height: 40),
            SizedBox(
              width: double.infinity,
              height: 56,
              child: ElevatedButton(
                style: ElevatedButton.styleFrom(
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
                onPressed: _isSaving ? null : _saveTransaction,
                child: const Text(
                  'Lưu giao dịch',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTypeToggle(ColorScheme colors) {
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Expanded(
            child: GestureDetector(
              onTap: () => _setIsExpense(true),
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 12),
                decoration: BoxDecoration(
                  color: _isExpense ? colors.error : Colors.transparent,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Center(
                  child: Text(
                    'Tiền chi',
                    style: TextStyle(
                      color: _isExpense
                          ? colors.onError
                          : colors.onSurfaceVariant,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ),
            ),
          ),
          Expanded(
            child: GestureDetector(
              onTap: () => _setIsExpense(false),
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 12),
                decoration: BoxDecoration(
                  color: !_isExpense ? colors.primary : Colors.transparent,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Center(
                  child: Text(
                    'Tiền thu',
                    style: TextStyle(
                      color: !_isExpense
                          ? colors.onPrimary
                          : colors.onSurfaceVariant,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
