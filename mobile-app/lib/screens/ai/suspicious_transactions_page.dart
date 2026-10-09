import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'package:finance_ai_app/services/anomaly_check_service.dart';

/// Lists the expenses the behavior model flags as unusual, with a per-category chart.
/// The history is re-scanned on open (cached for the session); pull down to force a new scan.
class SuspiciousTransactionsPage extends StatefulWidget {
  const SuspiciousTransactionsPage({super.key});

  @override
  State<SuspiciousTransactionsPage> createState() =>
      _SuspiciousTransactionsPageState();
}

class _SuspiciousTransactionsPageState
    extends State<SuspiciousTransactionsPage> {
  bool _loading = true;
  AnomalyScan? _scan;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({bool forceRefresh = false}) async {
    if (!forceRefresh) setState(() => _loading = true);
    final scan = await AnomalyCheckService.scanHistory(
      forceRefresh: forceRefresh,
    );
    if (!mounted) return;
    setState(() {
      _scan = scan;
      _loading = false;
    });
  }

  String _fmt(double a) => NumberFormat.currency(
    locale: 'vi_VN',
    symbol: 'đ',
    decimalDigits: 0,
  ).format(a);

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Cảnh báo chi tiêu bất thường')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: () => _load(forceRefresh: true),
              child: _buildBody(colors),
            ),
    );
  }

  Widget _message(
    ColorScheme colors,
    IconData icon,
    String title,
    String subtitle, {
    bool retry = false,
  }) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(32),
      children: [
        const SizedBox(height: 80),
        Icon(icon, size: 56, color: colors.onSurfaceVariant),
        const SizedBox(height: 16),
        Text(
          title,
          textAlign: TextAlign.center,
          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 18),
        ),
        const SizedBox(height: 8),
        Text(
          subtitle,
          textAlign: TextAlign.center,
          style: TextStyle(color: colors.onSurfaceVariant),
        ),
        if (retry) ...[
          const SizedBox(height: 24),
          Center(
            child: FilledButton(
              onPressed: () => _load(forceRefresh: true),
              child: const Text('Thử lại'),
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildBody(ColorScheme colors) {
    final scan = _scan;
    if (scan == null) {
      return _message(
        colors,
        Icons.cloud_off_rounded,
        'Chưa kiểm tra được',
        'Không kết nối được dịch vụ phân tích. Hãy thử lại sau.',
        retry: true,
      );
    }
    if (scan.scoredCount == 0) {
      return _message(
        colors,
        Icons.hourglass_empty_rounded,
        'Chưa đủ dữ liệu',
        'Cần ít nhất 5 giao dịch để phân tích thói quen',
      );
    }
    if (scan.flaggedCount == 0) {
      return _message(
        colors,
        Icons.verified_rounded,
        'Không có giao dịch bất thường',
        'Đã kiểm tra ${scan.scoredCount} giao dịch, tất cả đều phù hợp với thói quen của bạn.',
      );
    }

    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(20),
      children: [
        Text(
          '${scan.flaggedCount}/${scan.scoredCount} giao dịch có dấu hiệu bất thường',
          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 18),
        ),
        if (scan.modelVersion.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              'Mô hình: ${scan.modelVersion}',
              style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
            ),
          ),
        const SizedBox(height: 20),
        _buildChart(colors, scan),
        const SizedBox(height: 20),
        for (final t in scan.flagged) ...[
          _buildTile(colors, t),
          const SizedBox(height: 12),
        ],
      ],
    );
  }

  Widget _buildChart(ColorScheme colors, AnomalyScan scan) {
    final entries = scan.flaggedByCategory.entries.toList();
    final maxY = entries.fold<int>(1, (m, e) => e.value > m ? e.value : m);
    return Container(
      height: 200,
      padding: const EdgeInsets.fromLTRB(12, 16, 12, 8),
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(20),
      ),
      child: BarChart(
        BarChartData(
          maxY: maxY + 1.0,
          gridData: const FlGridData(show: false),
          borderData: FlBorderData(show: false),
          barTouchData: BarTouchData(enabled: false),
          titlesData: FlTitlesData(
            topTitles: const AxisTitles(),
            rightTitles: const AxisTitles(),
            leftTitles: const AxisTitles(),
            bottomTitles: AxisTitles(
              sideTitles: SideTitles(
                showTitles: true,
                reservedSize: 32,
                getTitlesWidget: (value, meta) {
                  final i = value.toInt();
                  if (i < 0 || i >= entries.length) return const SizedBox();
                  return Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text(
                      entries[i].key,
                      style: const TextStyle(fontSize: 10),
                      overflow: TextOverflow.ellipsis,
                    ),
                  );
                },
              ),
            ),
          ),
          barGroups: [
            for (var i = 0; i < entries.length; i++)
              BarChartGroupData(
                x: i,
                barRods: [
                  BarChartRodData(
                    toY: entries[i].value.toDouble(),
                    color: Colors.redAccent,
                    width: 22,
                    borderRadius: BorderRadius.circular(6),
                  ),
                ],
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildTile(ColorScheme colors, FlaggedTransaction t) {
    final date = t.occurredAt == null
        ? ''
        : DateFormat('dd/MM/yyyy HH:mm').format(t.occurredAt!);
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  t.category,
                  style: const TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                  ),
                ),
              ),
              Text(
                _fmt(t.amount),
                style: const TextStyle(
                  fontWeight: FontWeight.bold,
                  color: Colors.redAccent,
                ),
              ),
            ],
          ),
          const SizedBox(height: 2),
          Text(
            date,
            style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Text('Rủi ro', style: TextStyle(color: colors.onSurfaceVariant)),
              const SizedBox(width: 8),
              Expanded(
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: LinearProgressIndicator(
                    value: t.riskScore.clamp(0.0, 1.0),
                    minHeight: 8,
                    color: Colors.redAccent,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Text('${(t.riskScore * 100).round()}%'),
            ],
          ),
          if (t.explanation.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              t.explanation,
              style: TextStyle(color: colors.onSurfaceVariant, fontSize: 13),
            ),
          ],
        ],
      ),
    );
  }
}
