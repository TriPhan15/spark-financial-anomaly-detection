import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/core/utils/currency_helper.dart';

void main() {
  group('CurrencyHelper.parseAmount', () {
    test('keeps only the digits of what was typed', () {
      expect(CurrencyHelper.parseAmount('45000'), 45000.0);
      expect(CurrencyHelper.parseAmount(' 1.250.000 đ '), 1250000.0);
      expect(CurrencyHelper.parseAmount('2,500,000'), 2500000.0);
    });

    test('is null for an empty field, text without digits, and zero', () {
      expect(CurrencyHelper.parseAmount(''), isNull);
      expect(CurrencyHelper.parseAmount('abc'), isNull);
      expect(CurrencyHelper.parseAmount('0'), isNull);
      expect(CurrencyHelper.parseAmount('000'), isNull);
    });
  });
}
