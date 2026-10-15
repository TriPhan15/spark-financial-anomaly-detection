import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/core/utils/password_policy.dart';

// The cases mirror core-backend/.../Security/StrongPasswordTest.java: same boundaries, same verdicts.
void main() {
  group('PasswordPolicy.validate accepts', () {
    final accepted = <String, String>{
      'the shortest allowed (8 characters)': 'abcdefg1',
      'letters, digits and symbols': 'Passw0rd!',
      'a digit first': '1234567a',
      'non-ASCII letters': 'Mật-khẩu-mạnh-2026',
      'the longest allowed (72 characters)': 'a1${'b' * 70}',
    };
    accepted.forEach((name, password) {
      test(name, () => expect(PasswordPolicy.validate(password), isNull));
    });
  });

  group('PasswordPolicy.validate rejects', () {
    const lengthMessage = 'Mật khẩu phải từ 8 đến 72 ký tự.';
    const letterAndDigitMessage =
        'Mật khẩu phải chứa ít nhất một chữ cái và một chữ số.';

    final rejected = <String, List<String>>{
      '7 characters': ['abcdef1', lengthMessage],
      '73 characters': ['a1${'b' * 71}', lengthMessage],
      'no digit': ['abcdefgh', letterAndDigitMessage],
      'no letter': ['12345678', letterAndDigitMessage],
      'symbols only': ['!@#\$%^&*()', letterAndDigitMessage],
    };
    rejected.forEach((name, expectation) {
      test(name, () {
        expect(PasswordPolicy.validate(expectation[0]), expectation[1]);
      });
    });

    test('an empty password', () {
      expect(PasswordPolicy.validate(''), 'Mật khẩu không được để trống.');
    });

    test('a password of spaces only', () {
      expect(
        PasswordPolicy.validate('        '),
        'Mật khẩu không được để trống.',
      );
    });
  });

  test('the boundaries are the ones the backend enforces', () {
    expect(PasswordPolicy.minLength, 8);
    expect(PasswordPolicy.maxLength, 72);
  });
}
