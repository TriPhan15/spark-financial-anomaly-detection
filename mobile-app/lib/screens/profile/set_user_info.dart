import 'package:flutter/material.dart';

import 'package:finance_ai_app/core/utils/email_format.dart';
import 'package:finance_ai_app/models/user_model.dart';
import 'package:finance_ai_app/screens/auth/login_page.dart';
import 'package:finance_ai_app/services/sqflite_helper.dart';
import 'package:finance_ai_app/widgets/common/custom_text_field.dart';
import 'package:finance_ai_app/widgets/common/primary_button.dart';

class SetUserInfoScreen extends StatefulWidget {
  final int userId;
  final bool returnToLoginOnSave;

  const SetUserInfoScreen({
    super.key,
    required this.userId,
    this.returnToLoginOnSave = false,
  });

  @override
  State<SetUserInfoScreen> createState() => _SetUserInfoScreenState();
}

class _SetUserInfoScreenState extends State<SetUserInfoScreen> {
  final _birthdateController = TextEditingController();
  final _emailController = TextEditingController();
  final _addressController = TextEditingController();
  final _phoneController = TextEditingController();

  bool _isLoading = true;
  UserModel? _user;

  @override
  void initState() {
    super.initState();
    _loadUser();
  }

  @override
  void dispose() {
    _birthdateController.dispose();
    _emailController.dispose();
    _addressController.dispose();
    _phoneController.dispose();
    super.dispose();
  }

  Future<void> _loadUser() async {
    final user = await SqfliteHelper.instance.getUserById(widget.userId);
    if (!mounted) return;

    if (user != null) {
      _birthdateController.text = user.birthdate ?? '';
      _emailController.text = user.email;
      _addressController.text = user.address ?? '';
      _phoneController.text = user.phone ?? '';
    }

    setState(() {
      _user = user;
      _isLoading = false;
    });
  }

  bool _isValidDob(String s) {
    final m = RegExp(r'^(\d{2})/(\d{2})/(\d{4})$').firstMatch(s);
    if (m == null) return false;
    final d = int.parse(m.group(1)!);
    final mo = int.parse(m.group(2)!);
    final y = int.parse(m.group(3)!);
    if (mo < 1 || mo > 12 || d < 1 || d > 31) return false;
    final dt = DateTime(y, mo, d);
    return dt.day == d && dt.month == mo && dt.year == y;
  }

  Future<void> _pickDob() async {
    final now = DateTime.now();
    DateTime initial = DateTime(now.year - 18, now.month, now.day);
    if (_isValidDob(_birthdateController.text.trim())) {
      final m = RegExp(
        r'^(\d{2})/(\d{2})/(\d{4})$',
      ).firstMatch(_birthdateController.text.trim())!;
      initial = DateTime(
        int.parse(m.group(3)!),
        int.parse(m.group(2)!),
        int.parse(m.group(1)!),
      );
    }
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(1900),
      lastDate: now,
    );
    if (picked != null) {
      final dd = picked.day.toString().padLeft(2, '0');
      final mm = picked.month.toString().padLeft(2, '0');
      _birthdateController.text = '$dd/$mm/${picked.year}';
    }
  }

  Future<void> _save() async {
    final current = _user;
    if (current == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Không tìm thấy người dùng.')),
      );
      return;
    }

    final birthdate = _birthdateController.text.trim();
    final email = _emailController.text.trim();
    final address = _addressController.text.trim();
    final phone = _phoneController.text.trim();

    if (email.isEmpty || !EmailFormat.isValid(email)) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Email không hợp lệ.')));
      return;
    }

    if (birthdate.isNotEmpty && !_isValidDob(birthdate)) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Ngày sinh phải đúng định dạng DD/MM/YYYY.'),
        ),
      );
      return;
    }

    if (phone.isNotEmpty && !RegExp(r'^0[0-9]{9}$').hasMatch(phone)) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Số điện thoại không hợp lệ (10 số, bắt đầu bằng 0).'),
        ),
      );
      return;
    }

    final updated = current.copyWith(
      email: email,
      birthdate: birthdate.isEmpty ? null : birthdate,
      address: address.isEmpty ? null : address,
      phone: phone.isEmpty ? null : phone,
    );

    try {
      await SqfliteHelper.instance.updateUser(updated);
      if (!mounted) return;

      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Đã lưu thông tin.')));

      if (widget.returnToLoginOnSave) {
        Navigator.of(context).pushAndRemoveUntil(
          MaterialPageRoute(builder: (_) => const LoginScreen()),
          (route) => false,
        );
      } else {
        Navigator.pop(context, true);
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Lưu thất bại: $e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: colors.surface,
      appBar: AppBar(
        title: const Text('Thiết lập thông tin'),
        automaticallyImplyLeading: !widget.returnToLoginOnSave,
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              padding: const EdgeInsets.all(24.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Icon(Icons.person_rounded, size: 92, color: colors.primary),
                  const SizedBox(height: 20),
                  Text(
                    'Hãy hoàn tất thông tin cá nhân để sử dụng đầy đủ tính năng.',
                    style: TextStyle(
                      fontSize: 16,
                      color: colors.onSurfaceVariant,
                    ),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 32),
                  CustomTextField(
                    controller: _birthdateController,
                    label: 'Ngày sinh (DD/MM/YYYY) - không bắt buộc',
                    keyboardType: TextInputType.datetime,
                    suffixIcon: IconButton(
                      icon: Icon(
                        Icons.calendar_today_rounded,
                        color: colors.primary,
                        size: 20,
                      ),
                      onPressed: _pickDob,
                    ),
                  ),
                  const SizedBox(height: 16),
                  CustomTextField(
                    controller: _emailController,
                    label: 'Email (khôi phục tài khoản)',
                    keyboardType: TextInputType.emailAddress,
                  ),
                  const SizedBox(height: 16),
                  CustomTextField(
                    controller: _addressController,
                    label: 'Địa chỉ - không bắt buộc',
                    keyboardType: TextInputType.streetAddress,
                  ),
                  const SizedBox(height: 16),
                  CustomTextField(
                    controller: _phoneController,
                    label: 'Số điện thoại',
                    keyboardType: TextInputType.phone,
                  ),
                  const SizedBox(height: 40),
                  PrimaryButton(
                    text: widget.returnToLoginOnSave
                        ? 'Lưu & Quay về đăng nhập'
                        : 'Lưu thay đổi',
                    onPressed: () {
                      _save();
                    },
                  ),
                ],
              ),
            ),
    );
  }
}
