import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/utils/password_policy.dart';
import 'package:finance_ai_app/models/user_model.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/services/sqflite_helper.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/widgets/common/custom_text_field.dart';
import 'package:finance_ai_app/widgets/common/primary_button.dart';
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';

enum _EditingField { email, password }

class AccountCredentialsScreen extends StatefulWidget {
  final int userId;

  const AccountCredentialsScreen({super.key, required this.userId});

  @override
  State<AccountCredentialsScreen> createState() =>
      _AccountCredentialsScreenState();
}

class _AccountCredentialsScreenState extends State<AccountCredentialsScreen> {
  /// Shown instead of the real password in full mode: the server never sends it.
  static const String _serverPasswordMask = '********';
  static const String _changePasswordFailedTitle = 'Đổi mật khẩu thất bại';
  static const String _sessionExpiredMessage =
      'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
  static const String _noConnectionMessage =
      'Không thể kết nối máy chủ. Vui lòng kiểm tra mạng và thử lại.';
  static const String _genericFailureMessage =
      'Đã xảy ra lỗi. Vui lòng thử lại.';

  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();

  bool _isLoading = true;
  UserModel? _user;

  _EditingField? _editingField;

  @override
  void initState() {
    super.initState();
    _loadUser();
  }

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _loadUser() async {
    final user = await AppRepository.instance.getCurrentUser(widget.userId);
    if (!mounted) return;

    if (user != null) {
      _emailController.text = user.email;
      _passwordController.text = user.password;
    }

    setState(() {
      _user = user;
      _isLoading = false;
    });
  }

  void _startEdit(_EditingField field) {
    if (_isLoading || _user == null) return;
    setState(() {
      _editingField = field;
    });
  }

  void _cancelEdit() {
    final current = _user;
    if (current != null) {
      _emailController.text = current.email;
      _passwordController.text = current.password;
    }

    setState(() {
      _editingField = null;
    });
  }

  String _maskPassword(String password) {
    if (AppConfig.isFull) return _serverPasswordMask;
    if (password.isEmpty) return '';
    final clampedLength = password.length > 12 ? 12 : password.length;
    return List.filled(clampedLength, '*').join();
  }

  Future<void> _saveEmail() async {
    final current = _user;
    if (current == null) return;

    final email = _emailController.text.trim();
    if (email.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Vui lòng nhập tên đăng nhập.')),
      );
      return;
    }

    if (AppConfig.isFull) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Chỉnh sửa tài khoản chưa hỗ trợ trên server (chỉ ở bản demo).',
          ),
        ),
      );
      return;
    }
    final updated = current.copyWith(email: email);
    try {
      await SqfliteHelper.instance.updateUser(updated);
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Đã cập nhật tài khoản.')));
      setState(() {
        _user = updated;
        _editingField = null;
      });
    } catch (e) {
      if (!mounted) return;
      final msg = e.toString().toLowerCase();
      if (msg.contains('unique') ||
          msg.contains('1555') ||
          msg.contains('2067')) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('Email đã tồn tại.')));
      } else {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Cập nhật thất bại: $e')));
      }
    }
  }

  Future<void> _changePassword({
    required String currentPassword,
    required String newPassword,
    required String confirmPassword,
  }) async {
    final current = _user;
    if (current == null) return;

    if (currentPassword.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Vui lòng nhập mật khẩu cũ.')),
      );
      return;
    }
    if (newPassword.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Vui lòng nhập mật khẩu mới.')),
      );
      return;
    }
    if (confirmPassword.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Vui lòng xác nhận mật khẩu mới.')),
      );
      return;
    }
    // In full mode the server never sends the password, so only the server can tell that the old one is wrong.
    if (!AppConfig.isFull && currentPassword != current.password) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Mật khẩu cũ không đúng.')));
      return;
    }
    final policyError = PasswordPolicy.validate(newPassword);
    if (policyError != null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(policyError)));
      return;
    }
    if (newPassword != confirmPassword) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Mật khẩu mới không khớp.')));
      return;
    }

    if (AppConfig.isFull) {
      await _changePasswordOnServer(
        currentPassword: currentPassword,
        newPassword: newPassword,
      );
      return;
    }
    final updated = current.copyWith(password: newPassword);
    try {
      await SqfliteHelper.instance.updateUser(updated);
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Đổi mật khẩu thành công.')));
      setState(() {
        _user = updated;
        _editingField = null;
      });
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Đổi mật khẩu thất bại: $e')));
    }
  }

  /// Full mode: PUT /api/auth/change-password. The server checks the old password; on failure the form stays open with
  /// what the user typed, on success it closes. The token that made the call is revoked by the server, the next request
  /// renews it silently (see the 401 interceptor of RemoteApiHelper).
  Future<void> _changePasswordOnServer({
    required String currentPassword,
    required String newPassword,
  }) async {
    if (SessionService.currentUserId == null) {
      await AppDialog.error(
        context,
        title: _changePasswordFailedTitle,
        message: _sessionExpiredMessage,
      );
      return;
    }

    try {
      await RemoteApiHelper.instance.changePassword(
        currentPassword: currentPassword,
        newPassword: newPassword,
      );
    } catch (e) {
      debugPrint('Đổi mật khẩu thất bại: $e');
      if (!mounted) return;
      await AppDialog.error(
        context,
        title: _changePasswordFailedTitle,
        message: _messageOf(e),
      );
      return;
    }

    if (!mounted) return;
    setState(() => _editingField = null);
    await AppDialog.success(
      context,
      title: 'Đổi mật khẩu thành công',
      message: 'Mật khẩu mới đã được áp dụng cho tài khoản của bạn.',
    );
  }

  static String _messageOf(Object error) {
    if (error is! DioException) return _genericFailureMessage;

    final status = error.response?.statusCode;
    if (status == HttpStatus.unauthorized || status == HttpStatus.forbidden) {
      return _sessionExpiredMessage;
    }
    final data = error.response?.data;
    final serverMessage = (data is Map ? data['message'] : null)
        ?.toString()
        .trim();
    if (serverMessage != null && serverMessage.isNotEmpty) return serverMessage;
    return error.response == null
        ? _noConnectionMessage
        : _genericFailureMessage;
  }

  Widget _buildEditableInfoRow(
    ColorScheme colors, {
    required String label,
    required String value,
    required VoidCallback onTap,
  }) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
        child: Row(
          children: [
            SizedBox(
              width: 160,
              child: Text(
                label,
                style: TextStyle(color: colors.onSurfaceVariant),
              ),
            ),
            Expanded(
              child: Text(
                value,
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
            ),
            const SizedBox(width: 10),
            Icon(Icons.edit_rounded, size: 20, color: colors.primary),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final user = _user;
    final colors = Theme.of(context).colorScheme;

    return Scaffold(
      backgroundColor: colors.surface,
      appBar: AppBar(title: const Text('Tài khoản')),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : (user == null)
          ? Center(
              child: Text(
                'Chưa có dữ liệu tài khoản.',
                style: TextStyle(color: colors.onSurfaceVariant),
              ),
            )
          : ListView(
              padding: const EdgeInsets.all(24.0),
              children: [
                Icon(
                  Icons.lock_outline_rounded,
                  size: 72,
                  color: colors.primary,
                ),
                const SizedBox(height: 16),
                Text(
                  'Chạm vào dòng cần sửa.',
                  style: TextStyle(
                    fontSize: 16,
                    color: colors.onSurfaceVariant,
                  ),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 20),
                Card(
                  margin: EdgeInsets.zero,
                  child: Column(
                    children: [
                      _buildEditableInfoRow(
                        colors,
                        label: 'Tên đăng nhập',
                        value: user.email,
                        onTap: () => _startEdit(_EditingField.email),
                      ),
                      // The theme divider: the white 6% line of the old dark design did not show on the light card.
                      const Divider(height: 1),
                      _buildEditableInfoRow(
                        colors,
                        label: 'Mật khẩu',
                        value: _maskPassword(user.password),
                        onTap: () => _startEdit(_EditingField.password),
                      ),
                    ],
                  ),
                ),
                if (_editingField == _EditingField.password) ...[
                  const SizedBox(height: 24),
                  const Text(
                    'Đổi mật khẩu',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 16),
                  _PasswordChangeForm(
                    onSave: _changePassword,
                    onCancel: _cancelEdit,
                  ),
                ],
                if (_editingField == _EditingField.email) ...[
                  const SizedBox(height: 24),
                  const Text(
                    'Chỉnh sửa tên đăng nhập',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 16),
                  CustomTextField(
                    controller: _emailController,
                    label: 'Tên đăng nhập (Email)',
                    keyboardType: TextInputType.emailAddress,
                  ),
                  const SizedBox(height: 20),
                  PrimaryButton(text: 'Lưu thay đổi', onPressed: _saveEmail),
                  const SizedBox(height: 8),
                  TextButton(
                    onPressed: _cancelEdit,
                    child: Text(
                      'Hủy',
                      style: TextStyle(color: colors.onSurfaceVariant),
                    ),
                  ),
                ],
              ],
            ),
    );
  }
}

class _PasswordChangeForm extends StatefulWidget {
  final Future<void> Function({
    required String currentPassword,
    required String newPassword,
    required String confirmPassword,
  })
  onSave;
  final VoidCallback onCancel;

  const _PasswordChangeForm({required this.onSave, required this.onCancel});

  @override
  State<_PasswordChangeForm> createState() => _PasswordChangeFormState();
}

class _PasswordChangeFormState extends State<_PasswordChangeForm> {
  final _currentController = TextEditingController();
  final _newController = TextEditingController();
  final _confirmController = TextEditingController();
  bool _isLoading = false;
  bool _obscureCurrent = true;
  bool _obscureNew = true;
  bool _obscureConfirm = true;

  @override
  void dispose() {
    _currentController.dispose();
    _newController.dispose();
    _confirmController.dispose();
    super.dispose();
  }

  Future<void> _handleSave() async {
    setState(() => _isLoading = true);
    await widget.onSave(
      currentPassword: _currentController.text,
      newPassword: _newController.text,
      confirmPassword: _confirmController.text,
    );
    if (!mounted) return;
    setState(() => _isLoading = false);
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        CustomTextField(
          controller: _currentController,
          label: 'Mật khẩu cũ',
          isPassword: true,
          obscureText: _obscureCurrent,
          obscuringCharacter: '*',
          suffixIcon: IconButton(
            icon: Icon(
              _obscureCurrent ? Icons.visibility_off : Icons.visibility,
            ),
            onPressed: () => setState(() => _obscureCurrent = !_obscureCurrent),
          ),
        ),
        const SizedBox(height: 12),
        CustomTextField(
          controller: _newController,
          label: 'Mật khẩu mới',
          isPassword: true,
          obscureText: _obscureNew,
          obscuringCharacter: '*',
          suffixIcon: IconButton(
            icon: Icon(_obscureNew ? Icons.visibility_off : Icons.visibility),
            onPressed: () => setState(() => _obscureNew = !_obscureNew),
          ),
        ),
        const SizedBox(height: 12),
        CustomTextField(
          controller: _confirmController,
          label: 'Xác nhận mật khẩu mới',
          isPassword: true,
          obscureText: _obscureConfirm,
          obscuringCharacter: '*',
          suffixIcon: IconButton(
            icon: Icon(
              _obscureConfirm ? Icons.visibility_off : Icons.visibility,
            ),
            onPressed: () => setState(() => _obscureConfirm = !_obscureConfirm),
          ),
        ),
        const SizedBox(height: 20),
        PrimaryButton(
          text: 'Lưu thay đổi',
          onPressed: _isLoading ? () {} : _handleSave,
        ),
        const SizedBox(height: 8),
        TextButton(
          onPressed: widget.onCancel,
          child: Text(
            'Hủy',
            style: TextStyle(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      ],
    );
  }
}
