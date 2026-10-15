import 'package:flutter/material.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';

class TransactionCategories {
  static const String all = 'Tất cả';

  static List<String> expenseSelectable = [];
  static List<String> incomeSelectable = [];
  static List<String> selectable = [];

  static Future<void> loadFromApi() async {
    try {
      final cats = await RemoteApiHelper.instance.fetchCategories();
      expenseSelectable.clear();
      incomeSelectable.clear();

      for (var c in cats) {
        final name = c['name'].toString();
        final type = c['type'].toString();
        if (type == 'EXPENSE') {
          expenseSelectable.add(name);
        } else {
          incomeSelectable.add(name);
        }
      }

      selectable = _uniquePreserveOrder([
        ...expenseSelectable,
        ...incomeSelectable,
      ]);
    } catch (e) {
      debugPrint("Lỗi nạp danh mục TransactionCategories: $e");
    }
  }

  static List<String> forType({required bool isExpense}) {
    return isExpense ? expenseSelectable : incomeSelectable;
  }

  static List<String> _uniquePreserveOrder(List<String> input) {
    final seen = <String>{};
    final output = <String>[];
    for (final item in input) {
      if (seen.add(item)) output.add(item);
    }
    return output;
  }

  static IconData iconFor(String category) {
    final c = category.toLowerCase();

    if (c == 'tất cả') return Icons.filter_list_rounded;

    if (c.contains('ăn') ||
        c.contains('uống') ||
        c.contains('phê') ||
        c.contains('vặt')) {
      return Icons.fastfood_rounded;
    }
    if (c.contains('đi') || c.contains('xe') || c.contains('xăng')) {
      return Icons.local_gas_station_rounded;
    }
    if (c.contains('hóa đơn') ||
        c.contains('điện') ||
        c.contains('nước') ||
        c.contains('internet')) {
      return Icons.receipt_rounded;
    }
    if (c.contains('y tế') || c.contains('sức khỏe')) {
      return Icons.medical_services_rounded;
    }
    if (c.contains('giáo dục') || c.contains('học')) {
      return Icons.school_rounded;
    }
    if (c.contains('nhà ở')) return Icons.house_rounded;
    if (c.contains('mua sắm') ||
        c.contains('quần áo') ||
        c.contains('shopping')) {
      return Icons.shopping_bag_rounded;
    }
    if (c.contains('giải trí') || c.contains('game') || c.contains('đồ chơi')) {
      return Icons.sports_esports_rounded;
    }
    if (c.contains('du lịch')) return Icons.flight_takeoff_rounded;
    if (c.contains('làm đẹp')) return Icons.face_retouching_natural_rounded;

    if (c.contains('lương') || c.contains('thu nhập')) {
      return Icons.monetization_on_rounded;
    }
    if (c.contains('thưởng')) return Icons.card_giftcard_rounded;

    return Icons.label_important_rounded;
  }
}
