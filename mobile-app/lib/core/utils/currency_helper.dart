class CurrencyHelper {
  static String formatVND(double amount, {bool showSign = true}) {
    final isNegative = amount < 0;
    final abs = amount.abs();

    final formatted = abs
        .toStringAsFixed(0)
        .replaceAllMapped(
          RegExp(r'(\d{1,3})(?=(\d{3})+(?!\d))'),
          (m) => '${m[1]},',
        );

    if (formatted == '0') return '0 đ';

    if (!showSign) {
      return '$formatted đ';
    }

    if (isNegative) {
      return '-$formatted đ';
    }

    return '+$formatted đ';
  }

  /// The whole amount typed into an amount field: everything that is not a digit is ignored (spaces, dots, commas,
  /// "đ"). Null when nothing usable was typed or when the amount is not above zero.
  static double? parseAmount(String input) {
    final digitsOnly = input.replaceAll(RegExp(r'[^0-9]'), '');
    if (digitsOnly.isEmpty) return null;
    final value = double.tryParse(digitsOnly);
    if (value == null || value <= 0) return null;
    return value;
  }
}
