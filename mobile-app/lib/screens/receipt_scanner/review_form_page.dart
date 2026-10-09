import 'dart:io';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/constants/transaction_categories.dart';
import 'package:finance_ai_app/core/extensions/build_context_extension.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/models/receipt_item_model.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/services/anomaly_check_service.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/services/sqflite_helper.dart';

class ReviewFormPage extends StatefulWidget {
  final String imagePath;

  const ReviewFormPage({super.key, required this.imagePath});

  @override
  State<ReviewFormPage> createState() => _ReviewFormPageState();
}

class _ReviewFormPageState extends State<ReviewFormPage> {
  late ReceiptModel _receipt;
  bool _isProcessing = true;
  bool _isSaving = false;
  double _donutTotal = 0;
  Map<String, dynamic>? _rawScan;
  String? _imageUrl;

  final TextEditingController _storeNameController = TextEditingController();
  final TextEditingController _dateController = TextEditingController(
    text: DateFormat('dd/MM/yyyy HH:mm').format(DateTime.now()),
  );
  String _selectedCategory = 'Ăn uống';

  @override
  void initState() {
    super.initState();
    _receipt = ReceiptModel(storeName: '', date: '', items: [], tax: 0);
    _callDonutApi();
  }

  @override
  void dispose() {
    _storeNameController.dispose();
    _dateController.dispose();
    super.dispose();
  }

  Future<void> _callDonutApi() async {
    try {
      final result = await RemoteApiHelper.instance.scanReceipt(
        widget.imagePath,
      );

      if (AppConfig.isFull) {
        try {
          _imageUrl = await RemoteApiHelper.instance.uploadImage(
            widget.imagePath,
          );
        } catch (e) {
          debugPrint('Upload anh receipt lỗi: $e');
        }
      }

      final seller = result['seller'] is Map
          ? Map<String, dynamic>.from(result['seller'])
          : <String, dynamic>{};

      final storeName = seller['seller_name']?.toString() ?? '';
      final timestamp = seller['timestamp']?.toString() ?? '';
      final n = seller['total_cost_number'];
      final total = n is num
          ? n.toDouble()
          : (double.tryParse(
                  (seller['total_cost'] ?? '0').toString().replaceAll(
                    RegExp(r'[^\d.]'),
                    '',
                  ),
                ) ??
                0.0);

      if (!mounted) return;
      setState(() {
        _rawScan = result;
        if (storeName.isNotEmpty) _storeNameController.text = storeName;
        if (timestamp.isNotEmpty) _dateController.text = timestamp;
        _donutTotal = total;
        _receipt = ReceiptModel(
          storeName: storeName,
          date: timestamp,
          items: [],
          tax: 0,
        );
        _isProcessing = false;
      });

      if (total == 0 && mounted) {
        _showSnack('Donut chưa đọc được tổng tiền từ ảnh — vui lòng nhập tay.');
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _isProcessing = false);
      _showSnack(
        'Quét hóa đơn thất bại (Kiểm tra AI service). Vui lòng nhập tay.',
      );
    }
  }

  void _showSnack(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _saveToDatabase() async {
    final userId = SessionService.currentUserId;
    if (userId == null) {
      _showSnack('Vui lòng đăng nhập lại.');
      return;
    }

    setState(() => _isSaving = true);

    DateTime parsedDate;
    try {
      parsedDate = DateFormat(
        'dd/MM/yyyy HH:mm',
      ).parse(_dateController.text.trim());
    } catch (_) {
      parsedDate = DateTime.now();
    }

    final storeName = _storeNameController.text.trim();
    final transaction = TransactionModel.draft(
      title: storeName.isEmpty ? _selectedCategory : storeName,
      category: _selectedCategory,
      amount: _donutTotal > 0 ? _donutTotal : _receipt.grandTotal,
      date: parsedDate,
      isExpense: true,
    );

    try {
      if (AppConfig.isFull) {
        await RemoteApiHelper.instance.addTransaction({
          'amount': transaction.amount,
          'note': transaction.title,
          'location': transaction.title.isEmpty
              ? 'Hóa đơn quét'
              : transaction.title,
          'paymentMethod': 'WALLET',
          'transactionDate': transaction.date.toIso8601String(),
          'categoryName': transaction.category,
          'isExpense': true,
          if (_rawScan != null) 'rawReceiptData': jsonEncode(_rawScan),
          if (_imageUrl != null) 'imageUrl': _imageUrl,
        });
      } else {
        await SqfliteHelper.instance.insertTransaction(transaction, userId);
      }
      AnomalyCheckService.clearScanCache();
      if (!mounted) return;
      Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      setState(() => _isSaving = false);
      _showSnack('Lưu thất bại: $e');
    }
  }

  Future<void> _checkAnomalyAndSave() async {
    if (_isSaving) return;
    setState(() => _isSaving = true);

    final amount = _donutTotal > 0 ? _donutTotal : _receipt.grandTotal;
    DateTime occurredAt;
    try {
      occurredAt = DateFormat(
        'dd/MM/yyyy HH:mm',
      ).parse(_dateController.text.trim());
    } catch (_) {
      occurredAt = DateTime.now();
    }

    final result = await AnomalyCheckService.check(
      amount: amount,
      occurredAt: occurredAt,
      category: _selectedCategory,
    );

    if (mounted) setState(() => _isSaving = false);
    if (!mounted) return;

    if (result?.isAnomaly == true) {
      final shouldSave = await _showAnomalyWarningDialog(result!, amount);
      if (shouldSave && mounted) await _saveToDatabase();
      return;
    }
    await _saveToDatabase();
  }

  Future<bool> _showAnomalyWarningDialog(
    AnomalyResult result,
    double amount,
  ) async {
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
          'Điểm rủi ro: $risk% (ngưỡng cảnh báo $threshold%)\n\n'
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

  Future<void> _pickCategory() async {
    if (TransactionCategories.expenseSelectable.isEmpty) {
      await TransactionCategories.loadFromApi();
    }

    if (!mounted) return;
    final picked = await showModalBottomSheet<String>(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (sheetContext) {
        final colors = Theme.of(sheetContext).colorScheme;
        return SafeArea(
          child: ListView(
            shrinkWrap: true,
            padding: const EdgeInsets.symmetric(vertical: 8),
            children: [
              const Padding(
                padding: EdgeInsets.fromLTRB(20, 12, 20, 8),
                child: Text(
                  'Chọn danh mục chi',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                ),
              ),
              ...TransactionCategories.expenseSelectable.map((cat) {
                final selected = cat == _selectedCategory;
                return ListTile(
                  leading: Icon(
                    TransactionCategories.iconFor(cat),
                    color: selected ? colors.primary : colors.onSurfaceVariant,
                  ),
                  title: Text(
                    cat,
                    style: TextStyle(
                      color: selected
                          ? colors.onSurface
                          : colors.onSurfaceVariant,
                      fontWeight: selected
                          ? FontWeight.bold
                          : FontWeight.normal,
                    ),
                  ),
                  trailing: selected
                      ? Icon(Icons.check_rounded, color: colors.primary)
                      : null,
                  onTap: () => Navigator.pop(context, cat),
                );
              }),
            ],
          ),
        );
      },
    );

    if (picked != null && mounted) setState(() => _selectedCategory = picked);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Xác nhận hóa đơn')),
      body: _isProcessing
          ? _buildProcessingState(colors)
          : _buildFormContent(colors, theme.semanticColors),
    );
  }

  Widget _buildProcessingState(ColorScheme colors) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const CircularProgressIndicator(),
          const SizedBox(height: 24),
          const Text(
            'Đang phân tích hóa đơn...',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          Text(
            'Vui lòng đợi trong giây lát',
            style: TextStyle(color: colors.onSurfaceVariant, fontSize: 14),
          ),
        ],
      ),
    );
  }

  Widget _buildFormContent(ColorScheme colors, SemanticColors semantic) {
    final fmt = NumberFormat.currency(
      locale: 'vi_VN',
      symbol: 'đ',
      decimalDigits: 0,
    );

    return SingleChildScrollView(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // The original photo of the receipt keeps its own colours in both themes: no filter over it.
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Image.file(
                  File(widget.imagePath),
                  width: 80,
                  height: 120,
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => Container(
                    width: 80,
                    height: 120,
                    decoration: BoxDecoration(
                      color: colors.surface,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Icon(
                      Icons.image_not_supported,
                      color: colors.onSurfaceVariant,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Trích xuất thành công',
                      style: TextStyle(
                        color: colors.primary,
                        fontWeight: FontWeight.bold,
                        fontSize: 16,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Vui lòng kiểm tra lại thông tin bên dưới và đối chiếu với ảnh gốc trước khi lưu.',
                      style: TextStyle(
                        color: colors.onSurfaceVariant,
                        fontSize: 13,
                        height: 1.4,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 32),

          _buildSectionTitle('Thông tin chung'),
          _buildTextField(
            colors,
            'Tên cửa hàng',
            _storeNameController,
            Icons.storefront,
          ),
          const SizedBox(height: 16),
          _buildTextField(
            colors,
            'Ngày giao dịch',
            _dateController,
            Icons.calendar_today,
          ),
          const SizedBox(height: 16),

          Text(
            'Danh mục',
            style: TextStyle(color: colors.onSurfaceVariant, fontSize: 14),
          ),
          const SizedBox(height: 8),
          InkWell(
            onTap: _pickCategory,
            borderRadius: BorderRadius.circular(12),
            child: Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: colors.surface,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: colors.outlineVariant),
              ),
              child: Row(
                children: [
                  Icon(
                    TransactionCategories.iconFor(_selectedCategory),
                    color: colors.onSurfaceVariant,
                  ),
                  const SizedBox(width: 12),
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
          _buildSectionTitle('Tổng kết'),
          Card(
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                children: [
                  _buildTotalRow(
                    colors,
                    semantic,
                    'Tạm tính:',
                    fmt.format(_donutTotal),
                  ),
                  const SizedBox(height: 8),
                  _buildTotalRow(
                    colors,
                    semantic,
                    'Thuế VAT/Khác:',
                    fmt.format(_receipt.tax),
                  ),
                  // The theme divider: the white 10% line of the old dark design did not show on the light card.
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 12),
                    child: Divider(height: 1),
                  ),
                  _buildTotalRow(
                    colors,
                    semantic,
                    'TỔNG TIỀN:',
                    fmt.format(_donutTotal),
                    isGrandTotal: true,
                  ),
                ],
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
              onPressed: _isSaving ? null : _checkAnomalyAndSave,
              // While saving the button is disabled, so its background is the disabled grey: the spinner takes the
              // primary colour to show on it.
              child: _isSaving
                  ? const SizedBox(
                      width: 24,
                      height: 24,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text(
                      'Xác nhận & Lưu CSDL',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Text(
        title,
        style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
      ),
    );
  }

  /// Text, fill, cursor and prefix icon come from the input theme; the label keeps the secondary text colour.
  Widget _buildTextField(
    ColorScheme colors,
    String label,
    TextEditingController controller,
    IconData icon,
  ) {
    final border = OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: BorderSide(color: colors.outlineVariant),
    );
    return TextFormField(
      controller: controller,
      decoration: InputDecoration(
        labelText: label,
        labelStyle: TextStyle(color: colors.onSurfaceVariant),
        prefixIcon: Icon(icon),
        filled: true,
        border: border,
        enabledBorder: border,
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: colors.primary),
        ),
      ),
    );
  }

  /// The grand total is the amount spent, so it takes the expense colour.
  Widget _buildTotalRow(
    ColorScheme colors,
    SemanticColors semantic,
    String label,
    String value, {
    bool isGrandTotal = false,
  }) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          label,
          style: TextStyle(
            color: isGrandTotal ? colors.onSurface : colors.onSurfaceVariant,
            fontSize: isGrandTotal ? 16 : 14,
            fontWeight: isGrandTotal ? FontWeight.bold : FontWeight.normal,
          ),
        ),
        Text(
          value,
          style: TextStyle(
            color: isGrandTotal ? semantic.expenseColor : colors.onSurface,
            fontSize: isGrandTotal ? 20 : 14,
            fontWeight: FontWeight.bold,
          ),
        ),
      ],
    );
  }
}
