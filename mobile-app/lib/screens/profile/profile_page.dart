import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/extensions/build_context_extension.dart';
import 'package:finance_ai_app/cubits/language_cubit.dart';
import 'package:finance_ai_app/cubits/theme_cubit.dart';
import 'package:finance_ai_app/models/user_model.dart';
import 'package:finance_ai_app/models/wallet_model.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/screens/budget/budget_screen.dart';
import 'package:finance_ai_app/screens/profile/account_info_screen.dart';
import 'package:finance_ai_app/screens/profile/savings_page.dart';
import 'package:finance_ai_app/screens/profile/settings_screen.dart';
import 'package:finance_ai_app/screens/linked/linked_services_page.dart';
import 'package:finance_ai_app/screens/auth/login_page.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/services/social_auth_service.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';
import 'package:finance_ai_app/widgets/dialogs/profile_bottom_sheets.dart';

class ProfilePage extends StatefulWidget {
  const ProfilePage({super.key});

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  bool _isLoading = true;
  UserModel? _user;
  List<WalletModel> _wallets = [];
  List<TransactionModel> _recentTransactions = [];

  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _load();
    });
  }

  Future<void> _load() async {
    final userId = SessionService.currentUserId;
    if (userId == null) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      return;
    }
    final user = await AppRepository.instance.getCurrentUser(userId);
    final wallets = await AppRepository.instance.getWallets(userId);
    final txs = await AppRepository.instance.getTransactions(userId);
    if (!mounted) return;
    final sorted = [...txs]..sort((a, b) => b.date.compareTo(a.date));
    setState(() {
      _isLoading = false;
      _user = user;
      _wallets = wallets;
      _recentTransactions = sorted.take(20).toList();
    });
  }

  String _initials(String name) {
    final parts = name
        .trim()
        .split(RegExp(r'\s+'))
        .where((p) => p.isNotEmpty)
        .toList();
    if (parts.isEmpty) return '?';
    final first = parts.first.isNotEmpty ? parts.first[0] : '';
    final last = parts.length > 1 && parts.last.isNotEmpty ? parts.last[0] : '';
    return (first + last).toUpperCase();
  }

  /// Signs out. In full mode the server is asked to revoke the tokens first (they are needed for that request), then the
  /// local session is ALWAYS cleared and the user goes back to the login screen, even when the server call fails: a
  /// network error must never leave the user signed in.
  Future<void> _logout() async {
    final confirmed = await AppDialog.confirm(
      context,
      title: 'Đăng xuất?',
      message: 'Bạn có chắc chắn muốn thoát?',
      confirmText: 'Đăng xuất',
      danger: true,
    );
    if (!confirmed || !mounted) return;

    try {
      if (AppConfig.isFull) await RemoteApiHelper.instance.logout();
      await SocialAuthService.instance.signOut();
    } catch (e) {
      // Both steps are best effort: whatever fails, the local sign-out below must still happen.
      debugPrint('Đăng xuất: bước phía server/mạng xã hội thất bại: $e');
    }
    await SessionService.clearSession();

    // Only the navigation needs the context: the session is already cleared even when this page is gone.
    if (!mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
      (route) => false,
    );
  }

  void _openSettings() {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => const SettingsScreen()),
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    if (_isLoading) {
      return _buildSkeletonLoading(colors);
    }

    final name = _user?.fullName ?? 'Người dùng';
    final email = _user?.email ?? '';
    final loc = context.loc;
    final themeMode = context.select(
      (ThemeCubit cubit) => cubit.state.themeMode,
    );
    final language = context.select(
      (LanguageCubit cubit) => cubit.state.language,
    );

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.only(bottom: 100),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _buildProfileHeader(colors, name, email),
              const SizedBox(height: 24),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Column(
                  children: [
                    _buildSection(
                      colors,
                      title: 'Tài khoản',
                      icon: Icons.manage_accounts_rounded,
                      items: [
                        _SectionItem(
                          icon: Icons.person_rounded,
                          label: 'Thông tin tài khoản',
                          onTap: () => Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (_) => const AccountInfoScreen(),
                            ),
                          ).then((_) => _load()),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    _buildSection(
                      colors,
                      title: 'Tiện ích',
                      icon: Icons.widgets_rounded,
                      items: [
                        _SectionItem(
                          icon: Icons.account_balance_rounded,
                          label: 'Nguồn tiền',
                          onTap: () => ProfileBottomSheets.showBankSources(
                            context,
                            _wallets,
                          ),
                        ),
                        _SectionItem(
                          icon: Icons.link_rounded,
                          label: 'Dịch vụ liên kết',
                          onTap: () => Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (_) => const LinkedServicesPage(),
                            ),
                          ),
                        ),
                        _SectionItem(
                          icon: Icons.savings_rounded,
                          label: 'Sổ tiết kiệm',
                          onTap: () => Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (_) => const SavingsPage(),
                            ),
                          ),
                        ),
                        _SectionItem(
                          icon: Icons.pie_chart_rounded,
                          label: 'Ngân sách',
                          onTap: () => Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (_) => const BudgetScreen(),
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    _buildSection(
                      colors,
                      title: 'Cài đặt',
                      icon: Icons.settings_rounded,
                      items: [
                        _SectionItem(
                          icon: Icons.language_rounded,
                          label: loc.language,
                          subtitle: loc.languageLabel(language),
                          onTap: _openSettings,
                        ),
                        _SectionItem(
                          icon: Icons.notifications_rounded,
                          label: 'Thông báo',
                          onTap: () =>
                              ProfileBottomSheets.showNotificationSettings(
                                context,
                              ),
                        ),
                        _SectionItem(
                          icon: Icons.lock_rounded,
                          label: 'Bảo mật & Quyền riêng tư',
                          onTap: () => ProfileBottomSheets.showSecurityMenu(
                            context,
                            _recentTransactions,
                          ),
                        ),
                        _SectionItem(
                          icon: Icons.palette_rounded,
                          label: loc.theme,
                          subtitle: loc.themeModeLabel(themeMode),
                          onTap: _openSettings,
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    _buildSection(
                      colors,
                      title: 'Hỗ trợ',
                      icon: Icons.help_rounded,
                      items: [
                        _SectionItem(
                          icon: Icons.help_outline_rounded,
                          label: 'Hướng dẫn sử dụng',
                          onTap: () => ProfileBottomSheets.showHelp(context),
                        ),
                        _SectionItem(
                          icon: Icons.feedback_rounded,
                          label: 'Gửi phản hồi',
                          onTap: () =>
                              ProfileBottomSheets.showFeedback(context),
                        ),
                        _SectionItem(
                          icon: Icons.info_outline_rounded,
                          label: 'Về ứng dụng',
                          subtitle: 'v1.0.0',
                          onTap: () => ProfileBottomSheets.showAbout(context),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    _buildSection(
                      colors,
                      title: '',
                      icon: Icons.more_horiz_rounded,
                      hideHeader: true,
                      items: [
                        _SectionItem(
                          icon: Icons.logout_rounded,
                          label: 'Đăng xuất',
                          color: colors.error,
                          onTap: _logout,
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildProfileHeader(ColorScheme colors, String name, String email) {
    final initials = _initials(name);
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  'Cá nhân',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        CircleAvatar(
          radius: 44,
          backgroundColor: colors.primary,
          child: Text(
            initials,
            style: TextStyle(
              color: colors.onPrimary,
              fontSize: 28,
              fontWeight: FontWeight.bold,
            ),
          ),
        ),
        const SizedBox(height: 12),
        Text(
          name,
          style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 2),
        Text(
          email,
          style: TextStyle(color: colors.onSurfaceVariant, fontSize: 13),
        ),
      ],
    );
  }

  Widget _buildSection(
    ColorScheme colors, {
    required String title,
    required IconData icon,
    required List<_SectionItem> items,
    bool hideHeader = false,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (!hideHeader)
          Padding(
            padding: const EdgeInsets.only(bottom: 8, left: 4),
            child: Row(
              children: [
                Icon(icon, color: colors.primary, size: 17),
                const SizedBox(width: 6),
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
          ),
        // A Material, not a coloured Container: ListTile paints its background and ink splashes on the nearest Material,
        // so a coloured DecoratedBox in between would hide them (Flutter asserts on it).
        Material(
          color: colors.surface,
          borderRadius: BorderRadius.circular(16),
          clipBehavior: Clip.antiAlias,
          child: Column(
            children: items.asMap().entries.map((e) {
              final item = e.value;
              return Column(
                children: [
                  ListTile(
                    leading: Icon(
                      item.icon,
                      color: item.color ?? colors.primary,
                      size: 22,
                    ),
                    // Without a colour of its own, the title and the subtitle take the ListTile defaults of the
                    // theme: onSurface and onSurfaceVariant.
                    title: Text(
                      item.label,
                      style: TextStyle(color: item.color, fontSize: 14),
                    ),
                    subtitle: item.subtitle != null
                        ? Text(
                            item.subtitle!,
                            style: const TextStyle(fontSize: 12),
                          )
                        : null,
                    trailing: Icon(
                      Icons.chevron_right_rounded,
                      color: colors.onSurfaceVariant.withValues(alpha: 0.5),
                      size: 18,
                    ),
                    onTap: item.onTap,
                  ),
                  if (e.key < items.length - 1)
                    Divider(
                      height: 1,
                      indent: 60,
                      endIndent: 16,
                      color: colors.onSurfaceVariant.withValues(alpha: 0.1),
                    ),
                ],
              );
            }).toList(),
          ),
        ),
      ],
    );
  }

  Widget _buildSkeletonLoading(ColorScheme colors) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
          physics: const NeverScrollableScrollPhysics(),
          children: [
            Align(
              alignment: Alignment.center,
              child: _buildBox(
                colors,
                width: 88,
                height: 88,
                shape: BoxShape.circle,
              ),
            ),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.center,
              child: _buildBox(colors, width: 150, height: 20),
            ),
            const SizedBox(height: 32),
            _buildBox(colors, height: 150, borderRadius: 16),
            const SizedBox(height: 24),
            _buildBox(colors, height: 200, borderRadius: 16),
          ],
        ),
      ),
    );
  }

  Widget _buildBox(
    ColorScheme colors, {
    double? width,
    double? height,
    BoxShape shape = BoxShape.rectangle,
    double borderRadius = 0,
  }) {
    return Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        color: colors.surface.withValues(alpha: 0.6),
        shape: shape,
        borderRadius: shape == BoxShape.rectangle
            ? BorderRadius.circular(borderRadius)
            : null,
      ),
    );
  }
}

class _SectionItem {
  final IconData icon;
  final String label;
  final String? subtitle;
  final Color? color;
  final VoidCallback onTap;
  const _SectionItem({
    required this.icon,
    required this.label,
    required this.onTap,
    this.subtitle,
    this.color,
  });
}
