import 'package:dio/dio.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:flutter/material.dart';

import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/screens/main_scaffold.dart';
import 'package:finance_ai_app/widgets/common/primary_button.dart';
import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/repositories/local_auth_repository.dart';
import 'package:finance_ai_app/services/social_auth_service.dart';
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';
import 'package:finance_ai_app/screens/auth/forgot_password_page.dart';

import 'register_page.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _rememberMe = false;
  bool _obscure = true;
  bool _isLoginLoading = false;
  bool _isSocialLoading = false;
  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _handleLogin(
    BuildContext context, {
    required String email,
    required String password,
  }) async {
    final normalizedEmail = email.trim();
    final normalizedPassword = password;

    if (normalizedEmail.isEmpty || normalizedPassword.isEmpty) {
      await AppDialog.error(
        context,
        title: 'Thiếu thông tin',
        message: 'Vui lòng nhập email và mật khẩu.',
      );
      return;
    }
    if (_isLoginLoading || _isSocialLoading) return;
    setState(() => _isLoginLoading = true);
    try {
      if (AppConfig.isFull) {
        final result = await RemoteApiHelper.instance.login(
          normalizedEmail,
          normalizedPassword,
        );
        final data = (result['data'] as Map?) ?? result;

        final token = (data['token'] ?? result['token'])?.toString() ?? '';
        final refreshToken =
            (data['refreshToken'] ?? result['refreshToken'])?.toString() ?? '';

        final user = data['user'] as Map?;
        final userId =
            (user?['id'] ?? data['userId'] ?? result['userId'] ?? result['id'])
                as int?;

        if (token.isNotEmpty && refreshToken.isNotEmpty) {
          await SessionService.setTokens(token, refreshToken);
        }

        if (userId != null) {
          await SessionService.setCurrentUserId(userId);

          AppRepository.instance
              .getHomeSummary(userId)
              .catchError((_) => <String, dynamic>{});

          if (!context.mounted) return;
          Navigator.of(context).pushAndRemoveUntil(
            MaterialPageRoute(builder: (_) => const MainScaffold()),
            (route) => false,
          );
          return;
        }
      }

      final user = await LocalAuthRepository.instance.login(
        email: normalizedEmail,
        password: normalizedPassword,
      );

      if (!context.mounted) return;

      if (user?.id == null) {
        await AppDialog.error(
          context,
          title: 'Đăng nhập thất bại',
          message: 'Sai email hoặc mật khẩu.',
        );
        return;
      }

      await SessionService.setCurrentUserId(user!.id!);

      AppRepository.instance
          .getHomeSummary(user.id!)
          .catchError((_) => <String, dynamic>{});

      if (!context.mounted) return;
      Navigator.of(context).pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const MainScaffold()),
        (route) => false,
      );
    } on DioException catch (e) {
      if (!context.mounted) return;
      final data = e.response?.data;
      final serverMsg = (data is Map ? data['message'] : null)?.toString();
      await AppDialog.error(
        context,
        title: 'Đăng nhập thất bại',
        message: e.response?.statusCode == 401
            ? (serverMsg ?? 'Sai email hoặc mật khẩu!')
            : (serverMsg ?? 'Không thể kết nối máy chủ. Vui lòng thử lại.'),
      );
    } catch (e) {
      if (!context.mounted) return;
      await AppDialog.error(
        context,
        title: 'Đăng nhập thất bại',
        message: '$e',
      );
    } finally {
      if (mounted) setState(() => _isLoginLoading = false);
    }
  }

  /// The one way into the password recovery. The recovery screens never sign in: whatever happens there, the user
  /// comes back here, or to a new login screen, and signs in with the new password.
  void _openForgotPassword() {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => const ForgotPasswordScreen()),
    );
  }

  Future<void> _handleSocial(
    Future<Map<String, dynamic>> Function() signIn,
  ) async {
    if (!AppConfig.isFull) {
      return;
    }
    if (_isSocialLoading || _isLoginLoading) return;
    setState(() => _isSocialLoading = true);
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

        AppRepository.instance
            .getHomeSummary(userId)
            .catchError((_) => <String, dynamic>{});
      }

      if (!mounted) return;
      Navigator.of(context).pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const MainScaffold()),
        (route) => false,
      );
    } catch (e) {
      if (!mounted) return;
      if (!e.toString().contains('Đã hủy') &&
          !e.toString().contains('canceled')) {
        await AppDialog.error(
          context,
          title: 'Đăng nhập thất bại',
          message: '$e',
        );
      }
    } finally {
      if (mounted) setState(() => _isSocialLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    // The Scaffold takes the page colour of the theme; labels and fields keep the text colours it gives.
    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 16),
              Center(child: _logoPlaceholder(colors)),
              const SizedBox(height: 16),
              Text(
                'Ledgermind App',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: colors.primary,
                  fontSize: 26,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                'Quản lý tài chính thông minh',
                textAlign: TextAlign.center,
                style: TextStyle(color: colors.onSurfaceVariant, fontSize: 14),
              ),
              const SizedBox(height: 36),
              _label('Email'),
              _input(
                controller: _emailController,
                hint: 'Nhập địa chỉ email',
                keyboardType: TextInputType.emailAddress,
              ),
              const SizedBox(height: 18),
              _label('Mật khẩu'),
              _input(
                controller: _passwordController,
                hint: 'Nhập mật khẩu',
                obscure: _obscure,
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
              const SizedBox(height: 8),
              Row(
                children: [
                  SizedBox(
                    width: 24,
                    height: 24,
                    // Checked, the box takes the primary colour of the theme.
                    child: Checkbox(
                      value: _rememberMe,
                      onChanged: (v) =>
                          setState(() => _rememberMe = v ?? false),
                      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(6),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    'Ghi nhớ đăng nhập',
                    style: TextStyle(
                      color: colors.onSurfaceVariant,
                      fontSize: 13,
                    ),
                  ),
                  const Spacer(),
                  GestureDetector(
                    onTap: _openForgotPassword,
                    child: Text(
                      'Quên mật khẩu?',
                      style: TextStyle(
                        color: colors.primary,
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 24),
              PrimaryButton(
                text: _isLoginLoading ? 'Đang xử lý...' : 'Đăng nhập',
                onPressed: (_isLoginLoading || _isSocialLoading)
                    ? null
                    : () {
                        _handleLogin(
                          context,
                          email: _emailController.text,
                          password: _passwordController.text,
                        );
                      },
              ),
              const SizedBox(height: 16),
              Center(
                child: GestureDetector(
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => const RegisterScreen()),
                  ),
                  child: RichText(
                    text: TextSpan(
                      text: 'Chưa có tài khoản? ',
                      style: TextStyle(
                        color: colors.onSurfaceVariant,
                        fontSize: 14,
                      ),
                      children: [
                        TextSpan(
                          text: 'Đăng ký ngay',
                          style: TextStyle(
                            color: colors.primary,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 24),
              _orDivider(colors, 'Hoặc đăng nhập với'),
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
              const SizedBox(height: 16),
            ],
          ),
        ),
      ),
    );
  }

  Widget _logoPlaceholder(ColorScheme colors) {
    return Container(
      width: 88,
      height: 88,
      decoration: BoxDecoration(
        color: colors.primaryContainer,
        borderRadius: BorderRadius.circular(24),
      ),
      clipBehavior: Clip.antiAlias,
      child: Image.asset(
        'assets/images/logo.png',
        fit: BoxFit.cover,
        errorBuilder: (_, _, _) => Icon(
          Icons.add_photo_alternate_outlined,
          color: colors.onPrimaryContainer,
          size: 36,
        ),
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
  }) {
    return TextField(
      controller: controller,
      obscureText: obscure,
      keyboardType: keyboardType,
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
      onTap: _isSocialLoading ? null : onTap,
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
