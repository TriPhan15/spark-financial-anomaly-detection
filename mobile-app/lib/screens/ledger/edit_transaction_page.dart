import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'package:finance_ai_app/core/constants/transaction_categories.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/core/utils/currency_helper.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/widgets/common/custom_text_field.dart';
import 'package:finance_ai_app/widgets/common/primary_button.dart';
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';
import 'package:finance_ai_app/widgets/receipt/receipt_status_badge.dart';

/// Field names of the body of PUT /api/transactions/{id}; a field that is left out keeps its value on the server.
const String _fieldAmount = 'amount';
const String _fieldCategory = 'categoryName';
const String _fieldNote = 'note';

const int _httpUnauthorized = 401;
const int _httpForbidden = 403;

/// Form to correct the amount, category and note of a transaction. It needs the server, so the Ledger only offers it
/// when AppConfig.isFull. It sends just what the user changed, which leaves the date, the type and the receipt alone,
/// and pops with true once the server has saved the change.
class EditTransactionPage extends StatefulWidget {
  final TransactionModel transaction;

  const EditTransactionPage({super.key, required this.transaction});

  @override
  State<EditTransactionPage> createState() => _EditTransactionPageState();
}

class _EditTransactionPageState extends State<EditTransactionPage> {
  final TextEditingController _amountController = TextEditingController();
  final TextEditingController _noteController = TextEditingController();

  late String _category;
  List<String> _categories = const [];
  bool _isLoadingCategories = true;
  bool _categoriesUnavailable = false;
  bool _isSaving = false;

  TransactionModel get _original => widget.transaction;

  /// The amount in whole dong, which is all the form can show or type. The field and the comparison both use it, so an
  /// amount with decimals that the user does not touch is never rewritten.
  double get _originalWholeAmount => _original.amount.roundToDouble();

  /// The server titles a transaction without a note with its category, so an equal title means there is no note.
  String get _originalNote {
    final title = _original.title.trim();
    return title == _original.category.trim() ? '' : title;
  }

  @override
  void initState() {
    super.initState();
    _amountController.text = _originalWholeAmount.toStringAsFixed(0);
    _noteController.text = _originalNote;
    _category = _original.category;
    _categories = [_category];
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _loadCategories();
    });
  }

  @override
  void dispose() {
    _amountController.dispose();
    _noteController.dispose();
    super.dispose();
  }

  Future<void> _loadCategories() async {
    final isExpense = _original.isExpense;
    if (TransactionCategories.forType(isExpense: isExpense).isEmpty) {
      await TransactionCategories.loadFromApi();
      if (!mounted) return;
    }
    final loaded = TransactionCategories.forType(isExpense: isExpense);
    setState(() {
      // A copy, because the cached list is cleared whenever another screen reloads the categories. The category of the
      // transaction stays selectable even when the server list does not contain it.
      _categories = [
        if (!loaded.contains(_original.category)) _original.category,
        ...loaded,
      ];
      _categoriesUnavailable = loaded.isEmpty;
      _isLoadingCategories = false;
    });
  }

  /// The fields the user changed; empty when nothing differs from the transaction as it was opened.
  Map<String, dynamic> _changes(double amount) {
    final note = _noteController.text.trim();
    return {
      if (amount != _originalWholeAmount) _fieldAmount: amount,
      if (_category != _original.category) _fieldCategory: _category,
      if (note != _originalNote) _fieldNote: note,
    };
  }

  static String _messageOf(Object error) {
    if (error is! DioException) return 'Đã xảy ra lỗi. Vui lòng thử lại.';
    final response = error.response;
    if (response == null) {
      return 'Không thể kết nối máy chủ. Vui lòng kiểm tra mạng và thử lại.';
    }

    final data = response.data;
    final serverMessage = (data is Map ? data['message'] : null)
        ?.toString()
        .trim();
    if (serverMessage != null && serverMessage.isNotEmpty) return serverMessage;

    final status = response.statusCode;
    if (status == _httpUnauthorized || status == _httpForbidden) {
      return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
    }
    return 'Máy chủ chưa xử lý được yêu cầu. Vui lòng thử lại sau.';
  }

  /// Sends the changes. Returns null once the server has saved them, or the message to show when it did not.
  Future<String?> _send(String id, Map<String, dynamic> changes) async {
    try {
      await RemoteApiHelper.instance.updateTransaction(id, changes);
      return null;
    } catch (e) {
      debugPrint('Lỗi sửa giao dịch: $e');
      return _messageOf(e);
    }
  }

  /// The form can be closed while the request or a dialog is awaited, so `mounted` is checked before every dialog and
  /// before the pop: here for the validation dialogs (nothing is awaited before them) and after each await below.
  Future<void> _save() async {
    if (!mounted) return;
    if (SessionService.currentUserId == null) {
      await AppDialog.error(
        context,
        title: 'Phiên đăng nhập hết hạn',
        message: 'Vui lòng đăng nhập lại.',
      );
      return;
    }
    final id = _original.id;
    if (id == null) {
      await AppDialog.error(
        context,
        title: 'Không sửa được giao dịch',
        message: 'Giao dịch này chưa có mã trên máy chủ.',
      );
      return;
    }
    final amount = CurrencyHelper.parseAmount(_amountController.text);
    if (amount == null) {
      await AppDialog.error(
        context,
        title: 'Số tiền không hợp lệ',
        message: 'Vui lòng nhập số tiền lớn hơn 0.',
      );
      return;
    }
    final changes = _changes(amount);
    if (changes.isEmpty) {
      await AppDialog.info(
        context,
        title: 'Chưa có thay đổi',
        message: 'Hãy sửa số tiền, danh mục hoặc ghi chú trước khi lưu.',
      );
      return;
    }

    setState(() => _isSaving = true);
    final failure = await _send(id, changes);
    if (!mounted) return;
    setState(() => _isSaving = false);

    if (failure != null) {
      await AppDialog.error(
        context,
        title: 'Không lưu được giao dịch',
        message: failure,
      );
      return;
    }
    await AppDialog.success(
      context,
      title: 'Đã lưu thay đổi',
      message: 'Giao dịch đã được cập nhật.',
    );
    if (!mounted) return;
    Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Sửa giao dịch',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        leading: IconButton(
          icon: const Icon(Icons.close_rounded),
          onPressed: () {
            if (!mounted) return;
            Navigator.pop(context);
          },
        ),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _Summary(transaction: _original),
            const SizedBox(height: 24),
            CustomTextField(
              controller: _amountController,
              label: 'Số tiền (₫)',
              keyboardType: TextInputType.number,
            ),
            const SizedBox(height: 20),
            const _SectionLabel('Danh mục'),
            const SizedBox(height: 8),
            _CategoryChips(
              categories: _categories,
              selected: _category,
              isLoading: _isLoadingCategories,
              onSelected: (category) => setState(() => _category = category),
            ),
            if (_categoriesUnavailable)
              const _Hint(
                'Không tải được danh sách danh mục. Bạn vẫn có thể sửa số tiền và ghi chú.',
              ),
            const SizedBox(height: 20),
            CustomTextField(
              controller: _noteController,
              label: 'Ghi chú (tùy chọn)',
            ),
            const SizedBox(height: 32),
            PrimaryButton(
              text: _isSaving ? 'Đang lưu...' : 'Lưu thay đổi',
              onPressed: _isSaving ? null : _save,
            ),
          ],
        ),
      ),
    );
  }
}

/// What is being edited: the type, the date and the state of the receipt, none of which the form changes.
class _Summary extends StatelessWidget {
  static final DateFormat _displayDate = DateFormat('dd/MM/yyyy HH:mm');

  final TransactionModel transaction;

  const _Summary({required this.transaction});

  @override
  Widget build(BuildContext context) {
    final status = transaction.receiptStatus;
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: AppTheme.cardDecoration(colors),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: transaction.color.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(transaction.icon, color: transaction.color, size: 22),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  transaction.isExpense ? 'Khoản chi' : 'Khoản thu',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 2),
                Text(
                  _displayDate.format(transaction.date),
                  style: TextStyle(
                    color: colors.onSurfaceVariant,
                    fontSize: 12,
                  ),
                ),
                if (status != null) ...[
                  const SizedBox(height: 6),
                  ReceiptStatusBadge(status: status),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _CategoryChips extends StatelessWidget {
  final List<String> categories;
  final String selected;
  final bool isLoading;
  final ValueChanged<String> onSelected;

  const _CategoryChips({
    required this.categories,
    required this.selected,
    required this.isLoading,
    required this.onSelected,
  });

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final side = BorderSide(
      color: colors.onSurfaceVariant.withValues(alpha: 0.3),
    );
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        for (final category in categories)
          ChoiceChip(
            label: Text(category),
            selected: category == selected,
            showCheckmark: false,
            onSelected: (_) => onSelected(category),
            selectedColor: colors.primary,
            backgroundColor: colors.surface,
            side: side,
            labelStyle: TextStyle(
              color: category == selected ? colors.onPrimary : colors.onSurface,
            ),
          ),
        if (isLoading)
          const SizedBox(
            width: 20,
            height: 20,
            child: CircularProgressIndicator(strokeWidth: 2),
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
