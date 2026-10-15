/// Password rules of the app. They mirror the backend's @StrongPassword
/// (core-backend/.../Security/StrongPassword.java): change both together.
class PasswordPolicy {
  static const int minLength = 8;

  /// BCrypt only reads the first 72 bytes, so the backend rejects anything longer.
  static const int maxLength = 72;

  static final RegExp _letter = RegExp(r'\p{L}', unicode: true);
  static final RegExp _digit = RegExp(r'[0-9]');

  static bool hasMinLength(String password) => password.length >= minLength;

  static bool hasLetter(String password) => _letter.hasMatch(password);

  static bool hasDigit(String password) => _digit.hasMatch(password);

  /// The first rule [password] breaks, worded for the user, or null when it meets the policy.
  static String? validate(String password) {
    if (password.trim().isEmpty) {
      return 'Mật khẩu không được để trống.';
    }
    if (password.length < minLength || password.length > maxLength) {
      return 'Mật khẩu phải từ $minLength đến $maxLength ký tự.';
    }
    if (!hasLetter(password) || !hasDigit(password)) {
      return 'Mật khẩu phải chứa ít nhất một chữ cái và một chữ số.';
    }
    return null;
  }
}
