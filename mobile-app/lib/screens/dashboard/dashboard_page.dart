import 'dart:async';
import 'dart:ui';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/theme/app_theme.dart';
import 'package:finance_ai_app/models/user_model.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/models/wallet_model.dart';
import 'package:finance_ai_app/models/budget_progress.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/screens/budget/budget_screen.dart';
import 'package:finance_ai_app/screens/notifications/notification_screen.dart';
import 'package:finance_ai_app/screens/profile/savings_page.dart';
import 'package:finance_ai_app/screens/linked/linked_services_page.dart';
import 'package:finance_ai_app/screens/ai/suspicious_transactions_page.dart';
import 'package:finance_ai_app/services/anomaly_check_service.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/widgets/budget/budget_alert_presenter.dart';
import 'package:finance_ai_app/widgets/budget/budget_section.dart';
import 'package:finance_ai_app/widgets/notifications/notification_bell.dart';
import 'package:finance_ai_app/widgets/receipt/receipt_status_badge.dart';
import 'package:finance_ai_app/widgets/sync/sync_status_badge.dart';
import 'package:finance_ai_app/widgets/dialogs/dashboard_bottom_sheets.dart';

class DashboardPage extends StatefulWidget {
  final VoidCallback? onNavigateToAI;
  const DashboardPage({super.key, this.onNavigateToAI});
  @override
  State<DashboardPage> createState() => DashboardPageState();
}

class DashboardPageState extends State<DashboardPage>
    with AutomaticKeepAliveClientMixin, WidgetsBindingObserver {
  /// How many budgets the home screen lists; the rest is one line saying how many more there are.
  static const int _maxBudgetCards = 3;

  @override
  bool get wantKeepAlive => true;

  void reload() => _load();

  /// The number on the bell. The bell listens to it directly, so a new count repaints the bell, not the page.
  final ValueNotifier<int> _unreadNotifications = ValueNotifier<int>(0);

  /// Numbers the unread-count requests: only the answer to the latest one is shown, an older one arriving late is not.
  int _unreadRequest = 0;

  bool _isLoading = true;
  bool _balanceHidden = false;
  UserModel? _user;
  List<WalletModel> _wallets = [];
  List<BudgetProgress> _budgetProgress = [];
  bool _budgetsFailed = false;

  double _totalAssets = 0;
  double _monthlyIncome = 0;
  double _monthlyExpense = 0;
  double _prevMonthlyIncome = 0;
  double _prevMonthlyExpense = 0;
  List<TransactionModel> _recentTransactions = [];
  int _flaggedCount = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _unreadNotifications.dispose();
    super.dispose();
  }

  /// Back from the background: the server may have raised notifications meanwhile. The observer is removed in
  /// dispose, so a page that is gone never asks. There is no polling.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_refreshUnreadNotifications());
    }
  }

  Future<void> _load() async {
    setState(() => _isLoading = true);
    final userId = SessionService.currentUserId;
    if (userId == null) {
      setState(() => _isLoading = false);
      return;
    }
    // Not awaited: the bell has its own request and never holds up, or breaks, the rest of the page.
    unawaited(_refreshUnreadNotifications());

    final now = DateTime.now();
    final prevMonth = DateTime(now.year, now.month - 1);

    try {
      // Started together: the budgets do not depend on the summary, so the wait is that of the slower request.
      final budgetsRequest = AppRepository.instance.tryGetBudgets(userId);
      final summary = await AppRepository.instance.getHomeSummary(userId);
      final budgets = await budgetsRequest;

      if (!mounted) return;

      final List<TransactionModel> txs = summary['transactions'];

      double assets = 0, mInc = 0, mExp = 0, pInc = 0, pExp = 0;

      for (var tx in txs) {
        if (tx.isExpense) {
          assets -= tx.amount;
          if (tx.date.year == now.year && tx.date.month == now.month) {
            mExp += tx.amount;
          }
          if (tx.date.year == prevMonth.year &&
              tx.date.month == prevMonth.month) {
            pExp += tx.amount;
          }
        } else {
          assets += tx.amount;
          if (tx.date.year == now.year && tx.date.month == now.month) {
            mInc += tx.amount;
          }
          if (tx.date.year == prevMonth.year &&
              tx.date.month == prevMonth.month) {
            pInc += tx.amount;
          }
        }
      }

      // The budgets whose week or month contains today, the most used first.
      final budgetProgress = budgets == null
          ? <BudgetProgress>[]
          : (BudgetProgress.activeOn(now, budgets, txs)
              ..sort((a, b) => b.percent.compareTo(a.percent)));

      setState(() {
        _user = summary['user'];
        _wallets = summary['wallets'];
        _budgetProgress = budgetProgress;
        _budgetsFailed = budgets == null;

        _totalAssets = assets;
        _monthlyIncome = mInc;
        _monthlyExpense = mExp;
        _prevMonthlyIncome = pInc;
        _prevMonthlyExpense = pExp;
        _recentTransactions = txs.take(5).toList();

        _isLoading = false;
      });
      unawaited(_refreshFlaggedCount());
      // Not awaited: a dialog waiting for the user must not keep the pull-to-refresh spinner turning.
      unawaited(
        BudgetAlertPresenter.showNew(
          context,
          userId: userId,
          progresses: budgetProgress,
        ),
      );
    } catch (e) {
      debugPrint('Lỗi tải trang chủ: $e');
      if (!mounted) return;
      setState(() => _isLoading = false);
    }
  }

  /// The banner shows only when the scan succeeds and flags something; an error or no flag hides it.
  Future<void> _refreshFlaggedCount() async {
    final scan = await AnomalyCheckService.scanHistory();
    if (mounted) setState(() => _flaggedCount = scan?.flaggedCount ?? 0);
  }

  Widget _buildAnomalyBanner(ColorScheme colors) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => Navigator.push(
          context,
          MaterialPageRoute(builder: (_) => const SuspiciousTransactionsPage()),
        ),
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: Colors.redAccent.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(16),
          ),
          child: Row(
            children: [
              const Icon(Icons.warning_amber_rounded, color: Colors.redAccent),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  'Có $_flaggedCount giao dịch chi tiêu bất thường',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
              Icon(Icons.chevron_right_rounded, color: colors.onSurfaceVariant),
            ],
          ),
        ),
      ),
    );
  }

  /// Notifications are kept by the server only, so there is nothing to count without it (demo mode) or signed out. On
  /// failure the bell keeps its last number.
  Future<void> _refreshUnreadNotifications() async {
    if (!mounted || !AppConfig.isFull || SessionService.currentUserId == null) {
      return;
    }
    final request = ++_unreadRequest;
    try {
      final count = await RemoteApiHelper.instance.getUnreadNotificationCount();
      if (mounted && request == _unreadRequest) {
        _unreadNotifications.value = count;
      }
    } on DioException catch (e) {
      if (mounted) {
        debugPrint(
          'Lỗi tải số thông báo chưa đọc: HTTP ${e.response?.statusCode ?? e.type.name}',
        );
      }
    } on FormatException catch (e) {
      if (mounted) debugPrint('Lỗi đọc số thông báo chưa đọc: ${e.message}');
    }
  }

  Future<void> _openNotifications() async {
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => const NotificationScreen()),
    );
    // Whatever was read or marked there shows on the bell as soon as the user is back.
    if (mounted) unawaited(_refreshUnreadNotifications());
  }

  String _formatCurrency(double amount) => NumberFormat.currency(
    locale: 'vi_VN',
    symbol: 'đ',
    decimalDigits: 0,
  ).format(amount);
  @override
  Widget build(BuildContext context) {
    super.build(context);
    final colors = Theme.of(context).colorScheme;
    if (_isLoading) {
      return _buildLoadingScreen(colors);
    }

    return Stack(
      children: [
        _buildBackgroundGlow(colors),
        SafeArea(
          // No colours of its own: the spinner takes the primary colour and the surface of the theme.
          child: RefreshIndicator(
            onRefresh: _load,
            child: CustomScrollView(
              physics: const AlwaysScrollableScrollPhysics(),
              slivers: [
                SliverPadding(
                  padding: const EdgeInsets.only(bottom: 100),
                  sliver: SliverList(
                    delegate: SliverChildListDelegate([
                      _buildHeader(colors),
                      if (_flaggedCount > 0) _buildAnomalyBanner(colors),
                      if (_budgetProgress.isNotEmpty || _budgetsFailed)
                        Padding(
                          padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
                          child: BudgetSection(
                            progresses: _budgetProgress,
                            loadFailed: _budgetsFailed,
                            maxItems: _maxBudgetCards,
                            titleSize: 18,
                            onManage: _openBudgetScreen,
                          ),
                        ),
                      const SizedBox(height: 24),
                      _buildUtilityGrid(colors),
                      const SizedBox(height: 24),
                      _buildRecentTransactions(colors),
                    ]),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildLoadingScreen(ColorScheme colors) {
    final skeleton = colors.onSurfaceVariant.withValues(alpha: 0.15);
    return Stack(
      children: [
        _buildBackgroundGlow(colors),
        SafeArea(
          child: TweenAnimationBuilder<double>(
            tween: Tween<double>(begin: 0.3, end: 0.8),
            duration: const Duration(milliseconds: 1000),
            curve: Curves.easeInOut,
            builder: (context, opacity, child) {
              return Opacity(opacity: opacity, child: child);
            },
            onEnd: () {
              if (mounted) setState(() {});
            },
            child: ListView(
              padding: const EdgeInsets.only(bottom: 32, top: 10),
              physics: const NeverScrollableScrollPhysics(),
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          _buildSkeletonBox(
                            skeleton,
                            width: 80,
                            height: 14,
                            borderRadius: 4,
                          ),
                          const SizedBox(height: 8),
                          _buildSkeletonBox(
                            skeleton,
                            width: 150,
                            height: 22,
                            borderRadius: 6,
                          ),
                        ],
                      ),
                      _buildSkeletonBox(
                        skeleton,
                        width: 48,
                        height: 48,
                        shape: BoxShape.circle,
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 24),

                _buildSkeletonBox(
                  skeleton,
                  height: 180,
                  margin: const EdgeInsets.symmetric(horizontal: 20),
                  borderRadius: 24,
                ),
                const SizedBox(height: 32),

                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _buildSkeletonBox(
                        skeleton,
                        width: 100,
                        height: 20,
                        borderRadius: 6,
                      ),
                      const SizedBox(height: 16),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: List.generate(
                          4,
                          (_) => Column(
                            children: [
                              _buildSkeletonBox(
                                skeleton,
                                width: 56,
                                height: 56,
                                borderRadius: 16,
                              ),
                              const SizedBox(height: 8),
                              _buildSkeletonBox(
                                skeleton,
                                width: 50,
                                height: 12,
                                borderRadius: 4,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 32),

                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _buildSkeletonBox(
                        skeleton,
                        width: 160,
                        height: 20,
                        borderRadius: 6,
                      ),
                      const SizedBox(height: 16),
                      ...List.generate(
                        4,
                        (_) => Padding(
                          padding: const EdgeInsets.only(bottom: 12),
                          child: Row(
                            children: [
                              _buildSkeletonBox(
                                skeleton,
                                width: 48,
                                height: 48,
                                borderRadius: 16,
                              ),
                              const SizedBox(width: 16),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    _buildSkeletonBox(
                                      skeleton,
                                      width: double.infinity,
                                      height: 16,
                                      borderRadius: 4,
                                    ),
                                    const SizedBox(height: 8),
                                    _buildSkeletonBox(
                                      skeleton,
                                      width: 100,
                                      height: 12,
                                      borderRadius: 4,
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(width: 16),
                              _buildSkeletonBox(
                                skeleton,
                                width: 80,
                                height: 20,
                                borderRadius: 4,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildSkeletonBox(
    Color color, {
    double? width,
    double? height,
    EdgeInsetsGeometry? margin,
    BoxShape shape = BoxShape.rectangle,
    double borderRadius = 20,
  }) {
    return Container(
      width: width,
      height: height,
      margin: margin,
      decoration: BoxDecoration(
        color: color,
        shape: shape,
        borderRadius: shape == BoxShape.rectangle
            ? BorderRadius.circular(borderRadius)
            : null,
      ),
    );
  }

  Widget _buildBackgroundGlow(ColorScheme colors) {
    return Positioned(
      top: -100,
      left: -100,
      child: ImageFiltered(
        imageFilter: ImageFilter.blur(sigmaX: 80, sigmaY: 80),
        child: Container(
          width: 300,
          height: 300,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: colors.primary.withValues(alpha: 0.15),
          ),
        ),
      ),
    );
  }

  Widget _buildHeader(ColorScheme colors) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Xin chào,',
                    style: TextStyle(
                      color: colors.onSurfaceVariant,
                      fontSize: 14,
                    ),
                  ),
                  Text(
                    _user?.fullName ?? 'User',
                    style: const TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ],
              ),
              CircleAvatar(
                backgroundColor: colors.surface,
                child: NotificationBell(
                  unreadCount: _unreadNotifications,
                  onPressed: _openNotifications,
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),
          _buildAssetsCard(),
        ],
      ),
    );
  }

  Widget _buildAssetsCard() {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        gradient: AppTheme.purpleCardGradient,
        borderRadius: BorderRadius.circular(24),
        boxShadow: [
          BoxShadow(
            color: AppTheme.primary.withValues(alpha: 0.30),
            blurRadius: 24,
            offset: const Offset(0, 12),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'Tổng tài sản hiện tại',
                style: TextStyle(
                  color: Colors.white.withValues(alpha: 0.75),
                  fontSize: 14,
                ),
              ),
              GestureDetector(
                onTap: () => setState(() => _balanceHidden = !_balanceHidden),
                child: Icon(
                  _balanceHidden
                      ? Icons.visibility_off_rounded
                      : Icons.visibility_rounded,
                  color: Colors.white.withValues(alpha: 0.75),
                  size: 20,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 250),
            child: Text(
              _balanceHidden ? '••••••••' : _formatCurrency(_totalAssets),
              key: ValueKey(_balanceHidden),
              style: const TextStyle(
                color: Colors.white,
                fontSize: 36,
                fontWeight: FontWeight.w800,
              ),
            ),
          ),
          const SizedBox(height: 16),
          _buildTrendCapsule(),
        ],
      ),
    );
  }

  Widget _buildTrendCapsule() {
    final curNet = _monthlyIncome - _monthlyExpense;
    final prevNet = _prevMonthlyIncome - _prevMonthlyExpense;
    final diff = prevNet == 0
        ? (curNet > 0 ? 100.0 : (curNet < 0 ? -100.0 : 0.0))
        : (curNet - prevNet) / prevNet.abs() * 100;
    final up = diff >= 0;

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.18),
            borderRadius: BorderRadius.circular(20),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                up ? Icons.arrow_upward_rounded : Icons.arrow_downward_rounded,
                color: Colors.white,
                size: 14,
              ),
              const SizedBox(width: 4),
              Text(
                '${diff.abs().toStringAsFixed(1)}%',
                style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                  fontSize: 13,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 8),
        Text(
          'so với tháng trước',
          style: TextStyle(
            color: Colors.white.withValues(alpha: 0.75),
            fontSize: 12,
          ),
        ),
      ],
    );
  }

  void _openBudgetScreen() {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => const BudgetScreen()),
    ).then((_) {
      if (mounted) _load();
    });
  }

  Widget _buildUtilityGrid(ColorScheme colors) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Tiện ích',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 16),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              _buildUtilityIcon(colors, Icons.pie_chart_rounded, 'Báo cáo', () {
                if (widget.onNavigateToAI != null) {
                  widget.onNavigateToAI!();
                }
              }),
              _buildUtilityIcon(
                colors,
                Icons.savings_rounded,
                'Sổ tiết kiệm',
                () => _navigateTo(const SavingsPage()),
              ),
              _buildUtilityIcon(
                colors,
                Icons.account_balance_rounded,
                'Kết nối\nngân hàng',
                () => DashboardBottomSheets.showBankConnect(context),
              ),
              _buildUtilityIcon(colors, Icons.grid_view_rounded, 'Tất cả', () {
                DashboardBottomSheets.showAllFeatures(
                  context,
                  onSavings: () {
                    Navigator.pop(context);
                    _navigateTo(const SavingsPage());
                  },
                  onBankSources: () {
                    Navigator.pop(context);
                    DashboardBottomSheets.showBankSources(context, _wallets);
                  },
                  onLinkedServices: () async {
                    Navigator.pop(context);
                    final synced = await Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (_) => const LinkedServicesPage(),
                      ),
                    );
                    if (synced == true) reload();
                  },
                );
              }),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildUtilityIcon(
    ColorScheme colors,
    IconData icon,
    String label,
    VoidCallback onTap,
  ) {
    return GestureDetector(
      onTap: onTap,
      child: Column(
        children: [
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              color: colors.surface,
              borderRadius: BorderRadius.circular(16),
            ),
            child: Icon(icon, color: colors.primary, size: 28),
          ),
          const SizedBox(height: 8),
          Text(
            label,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: colors.onSurfaceVariant,
              fontSize: 12,
              height: 1.2,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRecentTransactions(ColorScheme colors) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Giao dịch gần đây',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 16),
          if (_recentTransactions.isEmpty)
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: colors.surface,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Center(
                child: Text(
                  'Chưa có giao dịch nào.',
                  style: TextStyle(color: colors.onSurfaceVariant),
                ),
              ),
            )
          else
            ..._recentTransactions.map(
              (tx) => Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: _buildTxItem(colors, tx),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildTxItem(ColorScheme colors, TransactionModel tx) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: tx.color.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Icon(tx.icon, color: tx.color),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  tx.title,
                  style: const TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  _formatTime(tx.date),
                  style: TextStyle(
                    color: colors.onSurfaceVariant,
                    fontSize: 12,
                  ),
                ),
                if (tx.receiptStatus != null) ...[
                  const SizedBox(height: 6),
                  ReceiptStatusBadge(status: tx.receiptStatus!),
                ],
                if (tx.isUnsynced) ...[
                  const SizedBox(height: 6),
                  SyncStatusBadge(status: tx.syncStatus),
                ],
              ],
            ),
          ),
          Text(
            '${tx.isExpense ? "- " : "+ "}${_formatCurrency(tx.amount)}',
            style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
          ),
        ],
      ),
    );
  }

  String _formatTime(DateTime date) {
    final now = DateTime.now();
    if (DateUtils.isSameDay(date, now)) {
      return 'Hôm nay, ${DateFormat('HH:mm').format(date)}';
    }
    if (DateUtils.isSameDay(date, now.subtract(const Duration(days: 1)))) {
      return 'Hôm qua, ${DateFormat('HH:mm').format(date)}';
    }
    if (now.difference(date).inDays < 7) {
      return DateFormat('EEEE, HH:mm', 'vi_VN').format(date);
    }
    return DateFormat('dd/MM/yyyy, HH:mm').format(date);
  }

  void _navigateTo(Widget page) {
    Navigator.push(context, MaterialPageRoute(builder: (_) => page));
  }
}
