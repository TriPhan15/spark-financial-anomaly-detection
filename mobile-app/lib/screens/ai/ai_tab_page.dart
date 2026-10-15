import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:dio/dio.dart';
import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/models/spending_range.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/widgets/ai/spending_range_filter.dart';
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';
import 'package:finance_ai_app/widgets/dialogs/ai_components.dart';

/// Asks the user for the days of a custom report range, starting from [initial]; null when the user gives up.
typedef PickDateRange =
    Future<DateTimeRange?> Function(
      BuildContext context,
      DateTimeRange initial,
    );

/// The date picker offers the same years as the Ledger's.
const int _pickerFirstYear = 2020;
const int _pickerLastYear = 2100;
const int _httpServerError = 500;

Future<DateTimeRange?> _showDateRangePicker(
  BuildContext context,
  DateTimeRange initial,
) {
  return showDateRangePicker(
    context: context,
    firstDate: DateTime(_pickerFirstYear),
    lastDate: DateTime(_pickerLastYear),
    initialDateRange: initial,
    helpText: 'Chọn khoảng thời gian',
    cancelText: 'Hủy',
    confirmText: 'Áp dụng',
    saveText: 'Áp dụng',
  );
}

class AITabPage extends StatefulWidget {
  /// [pickDateRange] opens the date range picker; only tests pass another one.
  const AITabPage({super.key, this.pickDateRange = _showDateRangePicker});

  final PickDateRange pickDateRange;

  @override
  State<AITabPage> createState() => _AITabPageState();
}

class _AITabPageState extends State<AITabPage> {
  bool _isLoading = true;
  List<TransactionModel> _transactions = [];
  List<Map<String, dynamic>> _spadePatterns = [];
  List<Map<String, dynamic>> _savingsGoals = [];

  bool _spadeLoading = false;
  bool _spadeFailed = false;

  /// Numbers each analysis; an answer that is not the latest one belongs to a filter the user has left.
  int _spadeRequestId = 0;
  SpendingPeriod _period = SpendingPeriod.month;
  SpendingRange? _customRange;
  Map<String, dynamic>? _budgetAdvice;
  bool _adviceLoading = false;

  List<TransactionModel> get _monthTxs {
    final now = DateTime.now();
    return _transactions
        .where((t) => t.date.year == now.year && t.date.month == now.month)
        .toList();
  }

  double get _monthIncome =>
      _monthTxs.where((t) => !t.isExpense).fold(0, (s, t) => s + t.amount);
  double get _monthExpense =>
      _monthTxs.where((t) => t.isExpense).fold(0, (s, t) => s + t.amount);

  /// Worked out from the clock each time, so "this week" and "this month" never go stale while the tab stays open.
  SpendingRange get _range =>
      SpendingRange.of(_period, DateTime.now(), custom: _customRange);

  @override
  void initState() {
    super.initState();
    _load();
    RemoteApiHelper.instance.dataChangedNotifier.addListener(_load);
    SessionService.needRefreshAI.addListener(_onNeedRefreshAIChanged);
  }

  @override
  void dispose() {
    RemoteApiHelper.instance.dataChangedNotifier.removeListener(_load);
    SessionService.needRefreshAI.removeListener(_onNeedRefreshAIChanged);
    super.dispose();
  }

  void _onNeedRefreshAIChanged() {
    if (SessionService.needRefreshAI.value) {
      if (mounted) _load();
      SessionService.needRefreshAI.value = false;
    }
  }

  Future<void> _load({bool reportErrors = false}) async {
    final userId = SessionService.currentUserId;
    if (userId == null) {
      if (mounted) setState(() => _isLoading = false);
      return;
    }

    final results = await Future.wait([
      AppRepository.instance
          .getTransactions(userId)
          .catchError((_) => <TransactionModel>[]),
      RemoteApiHelper.instance.fetchSavings().catchError(
        (_) => <Map<String, dynamic>>[],
      ),
    ]);

    final txs = results[0] as List<TransactionModel>;
    final goals = results[1] as List<Map<String, dynamic>>;

    if (!mounted) return;
    setState(() {
      _transactions = txs;
      _savingsGoals = goals;
      _isLoading = false;
    });

    _loadSpadePatterns(reportErrors: reportErrors);
    _loadBudgetAdvice(txs);
  }

  Map<String, dynamic>? _pickUrgentGoal() {
    final active = _savingsGoals
        .where(
          (g) => ((g['target'] ?? 0) as num) > ((g['current'] ?? 0) as num),
        )
        .toList();
    if (active.isEmpty) return null;

    final now = DateTime.now();
    return active.reduce((a, b) {
      final d1 = _daysLeftOf(a, now) ?? 999999;
      final d2 = _daysLeftOf(b, now) ?? 999999;
      return (d1 > 0 && d1 < d2) ? a : b;
    });
  }

  int? _daysLeftOf(Map<String, dynamic> g, DateTime now) {
    try {
      final p = (g['deadline'] ?? '').toString().split('/');
      return DateTime(
        int.parse(p[2]),
        int.parse(p[1]),
        int.parse(p[0]),
      ).difference(now).inDays;
    } catch (_) {
      return null;
    }
  }

  Future<void> _loadBudgetAdvice(List<TransactionModel> txs) async {
    final goal = _pickUrgentGoal();
    if (goal == null) return;

    setState(() => _adviceLoading = true);
    try {
      final data = txs
          .map(
            (t) => {
              'category': t.category,
              'amount': t.amount,
              'date': t.date.toIso8601String(),
              'isExpense': t.isExpense,
            },
          )
          .toList();
      final days = _daysLeftOf(goal, DateTime.now());

      final advice = await RemoteApiHelper.instance.recommendBudget(
        transactions: data,
        target: ((goal['target'] ?? 0) as num).toDouble(),
        current: ((goal['current'] ?? 0) as num).toDouble(),
        monthsLeft: (days != null && days > 0) ? (days / 30.0).ceil() : 1,
      );
      if (mounted) setState(() => _budgetAdvice = advice);
    } catch (_) {
    } finally {
      if (mounted) setState(() => _adviceLoading = false);
    }
  }

  void _onPeriodSelected(SpendingPeriod period) {
    if (period == SpendingPeriod.custom) {
      _pickCustomRange();
      return;
    }
    if (period == _period) return;
    setState(() => _period = period);
    _loadSpadePatterns(reportErrors: true);
  }

  Future<void> _pickCustomRange() async {
    final current = _range;
    final picked = await widget.pickDateRange(
      context,
      DateTimeRange(start: current.start, end: current.end),
    );
    if (!mounted || picked == null) return;
    setState(() {
      _period = SpendingPeriod.custom;
      _customRange = SpendingRange.custom(picked.start, picked.end);
    });
    _loadSpadePatterns(reportErrors: true);
  }

  static String _messageOf(Object error) {
    if (error is! DioException) return 'Đã xảy ra lỗi. Vui lòng thử lại.';
    final status = error.response?.statusCode;
    if (status == null) {
      return 'Không kết nối được tới máy chủ phân tích. Vui lòng kiểm tra mạng và thử lại.';
    }
    if (status >= _httpServerError) {
      return 'Máy chủ phân tích đang gặp sự cố. Vui lòng thử lại sau.';
    }
    return 'Máy chủ phân tích chưa xử lý được yêu cầu. Vui lòng thử lại sau.';
  }

  Future<List<Map<String, dynamic>>> _analyze(
    List<TransactionModel> txs,
  ) async {
    if (!AppConfig.isFull) return _computeLocalPatterns(txs);
    // Nothing to mine in an empty range, so the AI service is not called for it.
    if (txs.isEmpty) return const [];
    final data = txs
        .map(
          (t) => {
            'category': t.category,
            'amount': t.amount,
            'date': t.date.toIso8601String(),
            'isExpense': t.isExpense,
          },
        )
        .toList();
    return RemoteApiHelper.instance.analyzePatterns(data);
  }

  /// Mines the spending patterns of the days on screen. Only a call the user asked for ([reportErrors]: a filter change, the
  /// refresh button, a pull to refresh) opens an error dialog. The tab is built at start-up behind the others, so a failure
  /// of a background load is logged and shown on the card only.
  Future<void> _loadSpadePatterns({bool reportErrors = false}) async {
    final requestId = ++_spadeRequestId;
    final range = _range;
    final rangeTxs = _transactions
        .where((t) => range.contains(t.date))
        .toList();
    setState(() {
      _spadeLoading = true;
      _spadeFailed = false;
    });

    List<Map<String, dynamic>>? patterns;
    Object? failure;
    try {
      patterns = await _analyze(rangeTxs);
    } catch (e) {
      failure = e;
    }
    // The page is gone, or a newer request has taken over because the filter changed meanwhile: drop this answer.
    if (!mounted || requestId != _spadeRequestId) return;

    final loaded = patterns;
    final error = failure;
    setState(() {
      if (loaded != null) _spadePatterns = loaded;
      _spadeFailed = error != null;
      _spadeLoading = false;
    });
    if (error == null) return;

    debugPrint('Lỗi phân tích thói quen chi tiêu: $error');
    if (reportErrors) {
      await AppDialog.error(
        context,
        title: 'Không phân tích được thói quen chi tiêu',
        message: _messageOf(error),
      );
    }
  }

  List<Map<String, dynamic>> _computeLocalPatterns(List<TransactionModel> txs) {
    final expTxs = txs.where((t) => t.isExpense).toList()
      ..sort((a, b) => a.date.compareTo(b.date));
    final Map<String, int> counts = {};
    final Map<String, double> weights = {};
    final total = expTxs.fold(0.0, (s, t) => s + t.amount);

    for (int i = 0; i < expTxs.length - 1; i++) {
      if (expTxs[i + 1].date.difference(expTxs[i].date).inDays <= 7 &&
          expTxs[i].category != expTxs[i + 1].category) {
        final key = '${expTxs[i].category} → ${expTxs[i + 1].category}';
        counts[key] = (counts[key] ?? 0) + 1;
        weights[key] =
            (weights[key] ?? 0.0) +
            (total > 0
                ? (expTxs[i].amount + expTxs[i + 1].amount) / total
                : 0.0);
      }
    }

    final sorted = counts.entries.toList()
      ..sort((a, b) => (weights[b.key] ?? 0).compareTo(weights[a.key] ?? 0));
    return sorted
        .take(5)
        .map(
          (e) => {
            'pattern': e.key,
            'count': e.value,
            'wsupport': '${((weights[e.key] ?? 0) * 100).toStringAsFixed(1)}%',
          },
        )
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Phân tích AI',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 22),
        ),
      ),
      // The spinners take the primary colour of the theme, the refresh one on its surface.
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: () => _load(reportErrors: true),
              child: _buildContent(colors),
            ),
    );
  }

  /// The icon colours of the insight cards identify each card and stay the same in both themes, like chart series.
  Widget _buildContent(ColorScheme colors) {
    if (_transactions.isEmpty) {
      return ListView(
        padding: const EdgeInsets.all(20),
        children: [
          _buildInsightCard(
            colors,
            icon: Icons.ac_unit_rounded,
            iconColor: Colors.blueAccent,
            title: 'Chưa có dữ liệu',
            subtitle:
                'Hãy nhập giao dịch thực tế để hệ thống WCM-SPADE khai phá quy luật.',
          ),
          const SizedBox(height: 24),

          AiComponents.buildBudgetAdviceSection(
            colors,
            _budgetAdvice,
            _adviceLoading,
          ),
          const SizedBox(height: 100),
        ],
      );
    }

    final range = _range;
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        _buildSafeToSpendCard(colors),
        const SizedBox(height: 16),
        _buildInsightCard(
          colors,
          icon: Icons.trending_up_rounded,
          iconColor: colors.primary,
          title: 'Xu hướng chi tiêu',
          subtitle: _getSpendingTrend(),
        ),
        const SizedBox(height: 16),
        _buildInsightCard(
          colors,
          icon: Icons.category_rounded,
          iconColor: Colors.orange,
          title: 'Chi nhiều nhất',
          subtitle: _getTopCategory(),
        ),
        const SizedBox(height: 16),
        _buildInsightCard(
          colors,
          icon: Icons.savings_rounded,
          iconColor: Colors.amber,
          title: 'Mẹo tiết kiệm',
          subtitle: _getSavingTip(),
        ),
        const SizedBox(height: 16),
        _buildInsightCard(
          colors,
          icon: Icons.water_drop_rounded,
          iconColor: Colors.blue,
          title: 'So sánh tuần',
          subtitle: _getWeeklyComparison(),
        ),
        const SizedBox(height: 24),
        const Text(
          'Biểu đồ thu chi tuần',
          style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 12),
        SpendingRangeFilter(
          range: range,
          isRefreshing: _spadeLoading,
          onPeriodSelected: _onPeriodSelected,
          onRefresh: () => _loadSpadePatterns(reportErrors: true),
        ),
        const SizedBox(height: 16),
        _buildWeeklyChart(colors, range),
        const SizedBox(height: 24),
        AiComponents.buildSpadeSection(
          colors,
          _spadePatterns,
          _spadeLoading,
          hasError: _spadeFailed,
        ),
        const SizedBox(height: 24),

        AiComponents.build503020Card(
          colors,
          _monthTxs,
          _monthIncome,
          _monthExpense,
        ),
        const SizedBox(height: 24),
        AiComponents.buildBudgetAdviceSection(
          colors,
          _budgetAdvice,
          _adviceLoading,
        ),
        const SizedBox(height: 100),
      ],
    );
  }

  /// What was spent in each bar of the chart; income does not count.
  List<double> _expensesPerBucket(List<SpendingBucket> buckets) => [
    for (final bucket in buckets)
      _transactions
          .where((t) => t.isExpense && bucket.contains(t.date))
          .fold(0.0, (s, t) => s + t.amount),
  ];

  /// The four weeks of the current month, whatever period the chart shows.
  List<double> _getWeeklyExpenses() =>
      _expensesPerBucket(SpendingRange.month(DateTime.now()).buckets);

  String _getWeeklyComparison() {
    final amounts = _getWeeklyExpenses();
    if (amounts.every((a) => a == 0)) return 'Chưa có dữ liệu tuần này.';

    int maxIdx = 0;
    for (int i = 1; i < 4; i++) {
      if (amounts[i] > amounts[maxIdx]) maxIdx = i;
    }
    return 'Tuần ${maxIdx + 1} có chi tiêu cao nhất: ${_fmt(amounts[maxIdx])}.';
  }

  Widget _buildWeeklyChart(ColorScheme colors, SpendingRange range) {
    final buckets = range.buckets;
    final weekExpenses = _expensesPerBucket(buckets);
    final maxY = (weekExpenses.reduce((a, b) => a > b ? a : b) * 1.2).clamp(
      100.0,
      double.infinity,
    );
    // The bars take the primary colour; grid, labels and the touch tooltip take theme colours, so that they switch
    // between light and dark with the rest of the screen.
    final gridLine = FlLine(
      color: colors.onSurfaceVariant.withValues(alpha: 0.1),
      strokeWidth: 1,
    );
    final labelStyle = TextStyle(color: colors.onSurfaceVariant, fontSize: 12);
    final tooltipStyle = TextStyle(
      color: colors.onSurface,
      fontWeight: FontWeight.bold,
      fontSize: 14,
    );

    return Container(
      key: ValueKey(range),
      height: 200,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(24),
      ),
      child: BarChart(
        BarChartData(
          alignment: BarChartAlignment.spaceAround,
          borderData: FlBorderData(show: false),
          barTouchData: BarTouchData(
            touchTooltipData: BarTouchTooltipData(
              getTooltipColor: (_) => colors.surfaceContainer,
              getTooltipItem: (group, groupIndex, rod, rodIndex) =>
                  BarTooltipItem(rod.toY.toString(), tooltipStyle),
            ),
          ),
          gridData: FlGridData(
            show: true,
            drawVerticalLine: false,
            horizontalInterval: maxY / 4,
            getDrawingHorizontalLine: (v) => gridLine,
          ),
          titlesData: FlTitlesData(
            leftTitles: const AxisTitles(
              sideTitles: SideTitles(showTitles: false),
            ),
            topTitles: const AxisTitles(
              sideTitles: SideTitles(showTitles: false),
            ),
            rightTitles: const AxisTitles(
              sideTitles: SideTitles(showTitles: false),
            ),
            bottomTitles: AxisTitles(
              sideTitles: SideTitles(
                showTitles: true,
                getTitlesWidget: (v, _) => Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(buckets[v.toInt()].label, style: labelStyle),
                ),
              ),
            ),
          ),
          barGroups: List.generate(
            buckets.length,
            (i) => BarChartGroupData(
              x: i,
              barRods: [
                BarChartRodData(
                  toY: weekExpenses[i],
                  color: colors.primary,
                  width: 20,
                  borderRadius: BorderRadius.circular(6),
                  backDrawRodData: BackgroundBarChartRodData(
                    show: true,
                    toY: maxY,
                    color: colors.surfaceContainerHighest,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSafeToSpendCard(ColorScheme colors) {
    if (_monthIncome <= 0) {
      return _buildInsightCard(
        colors,
        icon: Icons.account_balance_wallet_rounded,
        iconColor: colors.primary,
        title: 'Hôm nay tiêu được bao nhiêu?',
        subtitle: 'Thêm khoản thu tháng này để tính số tiền an toàn.',
      );
    }

    double goalNeed = 0;
    final goal = _pickUrgentGoal();
    if (goal != null) {
      final target = ((goal['target'] ?? 0) as num).toDouble();
      final current = ((goal['current'] ?? 0) as num).toDouble();
      final days = _daysLeftOf(goal, DateTime.now()) ?? 1;
      goalNeed = ((target - current) / ((days > 0 ? days : 1) / 30.0).ceil())
          .clamp(0, double.infinity);
    }

    final daysLeft =
        (DateTime(DateTime.now().year, DateTime.now().month + 1, 0).day -
                DateTime.now().day +
                1)
            .clamp(1, 31);
    final safePerDay = (_monthIncome - _monthExpense - goalNeed) / daysLeft;
    final ok = safePerDay >= 0;
    final color = ok ? colors.primary : colors.error;

    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withValues(alpha: 0.35)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                Icons.account_balance_wallet_rounded,
                color: color,
                size: 22,
              ),
              const SizedBox(width: 8),
              const Text(
                'Hôm nay tiêu được bao nhiêu?',
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            '${_fmt(safePerDay)} / ngày',
            style: TextStyle(
              color: color,
              fontSize: 26,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            ok
                ? 'Còn lại chia đều $daysLeft ngày.'
                : 'Bạn đã chi vượt phần an toàn của tháng.',
            style: TextStyle(
              color: colors.onSurfaceVariant,
              fontSize: 13,
              height: 1.4,
            ),
          ),
        ],
      ),
    );
  }

  String _getSpendingTrend() {
    final lm = DateTime(DateTime.now().year, DateTime.now().month - 1);
    final lastTotal = _transactions
        .where(
          (t) =>
              t.isExpense && t.date.year == lm.year && t.date.month == lm.month,
        )
        .fold(0.0, (s, t) => s + t.amount);
    if (lastTotal == 0) return 'Tháng này bạn đã chi ${_fmt(_monthExpense)}.';

    final diff = ((_monthExpense - lastTotal) / lastTotal * 100);
    return 'Chi tiêu tháng này ${diff > 0 ? 'tăng' : 'giảm'} ${diff.abs().toStringAsFixed(1)}% so với tháng trước.';
  }

  String _getTopCategory() {
    if (_monthExpense <= 0) return 'Chưa có chi tiêu tháng này.';
    final Map<String, double> totals = {};
    for (var tx in _monthTxs.where((t) => t.isExpense)) {
      totals[tx.category] = (totals[tx.category] ?? 0) + tx.amount;
    }
    final top = totals.entries.reduce((a, b) => a.value > b.value ? a : b);
    return '${top.key} chiếm ${_fmt(top.value)} (${(top.value / _monthExpense * 100).toStringAsFixed(0)}% tổng chi).';
  }

  String _getSavingTip() {
    if (_savingsGoals.isEmpty) {
      return _monthIncome > _monthExpense
          ? 'Đang dư ${_fmt(_monthIncome - _monthExpense)}. Hãy lập mục tiêu!'
          : 'Thêm mục tiêu tiết kiệm để AI phân tích.';
    }
    return 'Bạn đang trong lộ trình tiết kiệm. Xem gợi ý phân bổ tiền bên dưới để đạt mục tiêu nhanh hơn!';
  }

  Widget _buildInsightCard(
    ColorScheme colors, {
    required IconData icon,
    required Color iconColor,
    required String title,
    required String subtitle,
  }) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: iconColor.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Icon(icon, color: iconColor, size: 28),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  subtitle,
                  style: TextStyle(
                    color: colors.onSurfaceVariant,
                    fontSize: 13,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _fmt(double a) => NumberFormat.currency(
    locale: 'vi_VN',
    symbol: 'đ',
    decimalDigits: 0,
  ).format(a);
}
