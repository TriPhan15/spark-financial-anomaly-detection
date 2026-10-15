import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/core/utils/email_format.dart';

void main() {
  group('isValid', () {
    test('accepts a name, one @ and a dotted domain, whatever the case', () {
      for (final email in [
        'an@example.com',
        'first.last+tag@mail.example.vn',
        'An.Nguyen@Gmail.COM',
      ]) {
        expect(EmailFormat.isValid(email), isTrue, reason: email);
      }
    });

    test('refuses what is not an address, spaces included', () {
      for (final email in [
        '',
        'an',
        'an@',
        '@example.com',
        'an@example',
        'an@@example.com',
        'an @example.com',
        'an@exa mple.com',
        ' an@example.com',
      ]) {
        expect(EmailFormat.isValid(email), isFalse, reason: '"$email"');
      }
    });
  });

  group('mask', () {
    test('keeps the first two characters of the name and the whole domain', () {
      expect(EmailFormat.mask('patuann15@gmail.com'), 'pa***@gmail.com');
      expect(EmailFormat.mask('abc@example.com'), 'ab***@example.com');
    });

    test('keeps one character of a name of one or two', () {
      expect(EmailFormat.mask('ab@x.io'), 'a***@x.io');
      expect(EmailFormat.mask('a@x.io'), 'a***@x.io');
    });

    test('hides the same way whatever the length of the name', () {
      expect(
        EmailFormat.mask('anh@x.io').length,
        EmailFormat.mask('anhnguyenvan@x.io').length,
      );
    });

    test('never cuts a character in two', () {
      expect(EmailFormat.mask('😀😀😀@x.io'), '😀😀***@x.io');
    });

    test('shows nothing of a text with no name before an @', () {
      expect(EmailFormat.mask('example.com'), '***');
      expect(EmailFormat.mask('@example.com'), '***');
    });
  });
}
