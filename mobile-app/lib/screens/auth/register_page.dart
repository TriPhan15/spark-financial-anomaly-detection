import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

import 'package:finance_ai_app/core/extensions/build_context_extension.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/core/utils/password_policy.dart';
import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/models/user_model.dart';
import 'package:finance_ai_app/screens/profile/set_user_info.dart';
import 'package:finance_ai_app/repositories/local_auth_repository.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/social_auth_service.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/screens/main_scaffold.dart';
import 'package:finance_ai_app/widgets/common/primary_button.dart';
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';

class RegisterScreen extends StatefulWidget {
  const RegisterScreen({super.key});

  @override
  State<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends State<RegisterScreen> {
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _fullNameController = TextEditingController();
  final _phoneController = TextEditingController();
  final _passwordFocus = FocusNode();

  bool _obscure = true;
  bool _hasMinLength = false;
  bool _hasUppercase = false;
  bool _hasLowercase = false;
  bool _hasNumber = false;
  bool _hasSpecialChar = false;

  @override
  void initState() {
    super.initState();
    _passwordFocus.addListener(() => setState(() {}));
  }

  void _checkPassword(String password) {
    setState(() {
      _hasMinLength = PasswordPolicy.hasMinLength(password);
      _hasUppercase = password.contains(RegExp(r'[A-Z]'));
      _hasLowercase = password.contains(RegExp(r'[a-z]'));
      _hasNumber = password.contains(RegExp(r'[0-9]'));
      _hasSpecialChar = password.contains(RegExp(r'[!@#$%^&*(),.?":{}|<>]'));
    });
  }

  bool _isPasswordValid() =>
      _hasMinLength &&
      _hasUppercase &&
      _hasLowercase &&
      _hasNumber &&
      _hasSpecialChar;

  Future<void> _handleRegister() async {
    final fullName = _fullNameController.text.trim();
    final email = _emailController.text.trim();
    final password = _passwordController.text;
    final phone = _phoneController.text.trim();

    if (fullName.isEmpty ||
        email.isEmpty ||
        password.isEmpty ||
        phone.isEmpty) {
      await AppDialog.error(
        context,
        title: 'Thiếu thông tin',
        message: 'Vui lòng nhập đủ thông tin (gồm số điện thoại).',
      );
      return;
    }

    final policyError = PasswordPolicy.validate(password);
    if (!_isPasswordValid() || policyError != null) {
      await AppDialog.error(
        context,
        title: 'Mật khẩu yếu',
        message: policyError ?? 'Mật khẩu chưa đạt yêu cầu.',
      );
      return;
    }

    try {
      if (AppConfig.isFull) {
        await RemoteApiHelper.instance.register(
          email: email,
          password: password,
          fullName: fullName,
          phone: phone,
        );
        if (!mounted) return;
        await AppDialog.success(
          context,
          title: 'Đăng ký thành công',
          message: 'Tài khoản đã được tạo. Vui lòng đăng nhập.',
        );
        if (!mounted) return;
        Navigator.pop(context);
        return;
      }

      final userId = await LocalAuthRepository.instance.register(
        UserModel(
          email: email,
          password: password,
          fullName: fullName,
          phone: phone,
        ),
      );

      if (!mounted) return;

      Navigator.pushReplacement(
        context,
        MaterialPageRoute(
          builder: (_) =>
              SetUserInfoScreen(userId: userId, returnToLoginOnSave: true),
        ),
      );
    } on DioException catch (e) {
      if (!mounted) return;

      final data = e.response?.data;
      final serverMsg = (data is Map ? data['message'] : null)?.toString();
      await AppDialog.error(
        context,
        title: 'Đăng ký thất bại',
        message: serverMsg ?? 'Không thể kết nối máy chủ. Vui lòng thử lại.',
      );
    } catch (e) {
      if (!mounted) return;

      final msg = e.toString().toLowerCase();
      if (msg.contains('unique') ||
          msg.contains('1555') ||
          msg.contains('2067')) {
        await AppDialog.error(
          context,
          title: 'Email đã tồn tại',
          message: 'Email này đã được đăng ký. Vui lòng dùng email khác.',
        );
      } else {
        await AppDialog.error(
          context,
          title: 'Đăng ký thất bại',
          message: '$e',
        );
      }
    }
  }

  Future<void> _handleSocial(
    Future<Map<String, dynamic>> Function() signIn,
  ) async {
    if (!AppConfig.isFull) {
      return;
    }
    try {
      final res = await signIn();
      final data = (res['data'] as Map?) ?? res;
      final token = (data['token'] ?? res['token'])?.toString() ?? '';
      final refreshToken =
          (data['refreshToken'] ?? res['refreshToken'])?.toString() ?? '';

      final user = data['user'] as Map?;
      final userId = (user?['id'] ?? data['userId'] ?? res['userId']) as int?;
      if (token.isNotEmpty && refreshToken.isNotEmpty) {
        await SessionService.setTokens(token, refreshToken);
      }

      if (userId != null) {
        await SessionService.setCurrentUserId(userId);
      }
      if (!mounted) return;
      Navigator.of(context).pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const MainScaffold()),
        (route) => false,
      );
    } catch (e) {
      if (!mounted) return;
      await AppDialog.error(context, title: 'Đăng ký thất bại', message: '$e');
    }
  }

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    _fullNameController.dispose();
    _phoneController.dispose();
    _passwordFocus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final semantic = theme.semanticColors;
    // The Scaffold and its app bar take the page colour of the theme; labels and fields keep the text colours it gives.
    return Scaffold(
      appBar: AppBar(title: const Text('Đăng ký')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 8),
              const Text(
                'Tạo tài khoản',
                style: TextStyle(fontSize: 26, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 6),
              Text(
                'Điền thông tin để bắt đầu quản lý tài chính.',
                style: TextStyle(color: colors.onSurfaceVariant, fontSize: 14),
              ),
              const SizedBox(height: 28),
              _label('Họ và tên'),
              _input(controller: _fullNameController, hint: 'Nhập họ và tên'),
              const SizedBox(height: 18),
              _label('Email'),
              _input(
                controller: _emailController,
                hint: 'Nhập địa chỉ email',
                keyboardType: TextInputType.emailAddress,
              ),
              const SizedBox(height: 18),
              _label('Số điện thoại'),
              _input(
                controller: _phoneController,
                hint: 'Nhập số điện thoại',
                keyboardType: TextInputType.phone,
              ),
              const SizedBox(height: 18),
              _label('Mật khẩu'),
              _input(
                controller: _passwordController,
                hint: 'Nhập mật khẩu',
                obscure: _obscure,
                focusNode: _passwordFocus,
                onChanged: _checkPassword,
                suffix: IconButton(
                  icon: Icon(
                    _obscure
                        ? Icons.visibility_off_outlined
                        : Icons.visibility_outlined,
                    color: colors.onSurfaceVariant,
                    size: 20,
                  ),
                  onPressed: () => setState(() => _obscure = !_obscure),
                ),
              ),
              AnimatedSize(
                duration: const Duration(milliseconds: 250),
                curve: Curves.easeInOut,
                child: _passwordFocus.hasFocus
                    ? _buildChecklist(colors, semantic)
                    : const SizedBox(width: double.infinity),
              ),
              const SizedBox(height: 28),
              PrimaryButton(text: 'Tạo tài khoản', onPressed: _handleRegister),
              const SizedBox(height: 24),
              _orDivider(colors, 'Hoặc đăng ký với'),
              const SizedBox(height: 20),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  _socialBtn(
                    colors,
                    Icons.g_mobiledata_rounded,
                    const Color(0xFFDB4437),
                    () => _handleSocial(
                      SocialAuthService.instance.signInWithGoogle,
                    ),
                  ),
                  const SizedBox(width: 20),
                  _socialBtn(
                    colors,
                    Icons.facebook,
                    const Color(0xFF1877F2),
                    () => _handleSocial(
                      SocialAuthService.instance.signInWithFacebook,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 24),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Text.rich(
                  TextSpan(
                    text: 'Bằng việc đăng ký, bạn đồng ý với ',
                    style: TextStyle(
                      color: colors.onSurfaceVariant,
                      fontSize: 12,
                    ),
                    children: [
                      TextSpan(
                        text: 'Điều khoản',
                        style: TextStyle(
                          color: colors.primary,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const TextSpan(text: ' và '),
                      TextSpan(
                        text: 'Điều kiện sử dụng',
                        style: TextStyle(
                          color: colors.primary,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                  textAlign: TextAlign.center,
                ),
              ),
              const SizedBox(height: 16),
            ],
          ),
        ),
      ),
    );
  }

  /// The password rules on a theme card (surface colour, no shadow).
  Widget _buildChecklist(ColorScheme colors, SemanticColors semantic) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: 16),
      padding: const EdgeInsets.all(16),
      decoration: AppTheme.cardDecoration(colors),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Mật khẩu cần:',
            style: TextStyle(fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 12),
          _conditionRow(
            colors,
            semantic,
            'Ít nhất ${PasswordPolicy.minLength} ký tự',
            _hasMinLength,
          ),
          _conditionRow(colors, semantic, 'Chữ viết hoa (A-Z)', _hasUppercase),
          _conditionRow(
            colors,
            semantic,
            'Chữ viết thường (a-z)',
            _hasLowercase,
          ),
          _conditionRow(colors, semantic, 'Chữ số (0-9)', _hasNumber),
          _conditionRow(
            colors,
            semantic,
            'Ký tự đặc biệt (!@#...)',
            _hasSpecialChar,
          ),
        ],
      ),
    );
  }

  /// A met rule shows the success colour and the text colour; an open one stays in the secondary text colour.
  Widget _conditionRow(
    ColorScheme colors,
    SemanticColors semantic,
    String text,
    bool isMet,
  ) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8.0),
      child: Row(
        children: [
          Icon(
            isMet ? Icons.check_circle : Icons.radio_button_unchecked,
            color: isMet ? semantic.successColor : colors.onSurfaceVariant,
            size: 20,
          ),
          const SizedBox(width: 8),
          Text(
            text,
            style: TextStyle(
              color: isMet ? colors.onSurface : colors.onSurfaceVariant,
              fontSize: 13,
            ),
          ),
        ],
      ),
    );
  }

  Widget _label(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8, left: 2),
      child: Align(
        alignment: Alignment.centerLeft,
        child: Text(
          text,
          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
        ),
      ),
    );
  }

  /// Text, hint, fill and outline come from the theme: onSurface, onSurfaceVariant and the inputDecorationTheme.
  Widget _input({
    required TextEditingController controller,
    required String hint,
    bool obscure = false,
    Widget? suffix,
    TextInputType? keyboardType,
    FocusNode? focusNode,
    ValueChanged<String>? onChanged,
  }) {
    return TextField(
      controller: controller,
      focusNode: focusNode,
      obscureText: obscure,
      keyboardType: keyboardType,
      onChanged: onChanged,
      decoration: InputDecoration(hintText: hint, suffixIcon: suffix),
    );
  }

  Widget _orDivider(ColorScheme colors, String text) {
    final line = colors.onSurfaceVariant.withValues(alpha: 0.3);
    return Row(
      children: [
        Expanded(child: Divider(color: line)),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Text(
            text,
            style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
          ),
        ),
        Expanded(child: Divider(color: line)),
      ],
    );
  }

  /// A round button on the surface colour; the brand colour of the icon stays at 3:1 or more on it in both themes.
  Widget _socialBtn(
    ColorScheme colors,
    IconData icon,
    Color color,
    VoidCallback onTap,
  ) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(30),
      child: Container(
        width: 56,
        height: 56,
        decoration: BoxDecoration(
          color: colors.surface,
          shape: BoxShape.circle,
          boxShadow: AppTheme.softShadowFor(colors),
        ),
        child: Icon(icon, color: color, size: 30),
      ),
    );
  }
}
