/// Email checks of the app, made before an address reaches the server.
class EmailFormat {
  /// Something@something.something without spaces: the check the profile form has always made.
  static final RegExp _pattern = RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$');

  /// Stands for the hidden part of an address; always the same length, so it does not tell how long the name is.
  static const String _maskFill = '***';

  /// How many characters of the name before the @ stay visible; names this short or shorter keep only one.
  static const int _visibleNameLength = 2;

  static bool isValid(String email) => _pattern.hasMatch(email);

  /// [email] with most of its name hidden and its domain kept, e.g. "an***@gmail.com": enough for its owner to
  /// recognise it, not for someone looking over their shoulder. Characters are counted as code points, so an emoji is
  /// never cut in two.
  static String mask(String email) {
    final at = email.lastIndexOf('@');
    if (at <= 0) return _maskFill;
    final name = email.substring(0, at).runes;
    final visible = name.length > _visibleNameLength ? _visibleNameLength : 1;
    return '${String.fromCharCodes(name.take(visible))}$_maskFill${email.substring(at)}';
  }
}
