import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:finance_ai_app/models/transaction_model.dart';

/// Sections of the AI tab. Each builder takes the colour scheme that the calling screen read once in its build.
///
/// Amber marks the money-allocation advice, blue and orange the needs and wants of the 50/30/20 rule: identity
/// colours that stay the same in both themes, like chart series, and are bright enough on the dark surface.
class AiComponents {
  static String _fmt(double amount) => NumberFormat.currency(
    locale: 'vi_VN',
    symbol: 'đ',
    decimalDigits: 0,
  ).format(amount);

  static String formatAiPattern(String rawPattern) {
    Map<String, String> dict = {
      'an uong': 'Ăn uống',
      'di lai': 'Đi lại',
      'hoa don': 'Hóa đơn',
      'suc khoe': 'Sức khỏe',
      'giao duc': 'Giáo dục',
      'nha o': 'Nhà ở',
      'dien nuoc': 'Điện nước',
      'internet': 'Internet',
      'xang': 'Xăng',
      'y te': 'Y tế',
      'du lich': 'Du lịch',
      'giai tri': 'Giải trí',
      'mua sam': 'Mua sắm',
      'ca phe': 'Cà phê',
      'an vat': 'Ăn vặt',
      'lam dep': 'Làm đẹp',
      'game': 'Game',
      'shopping': 'Shopping',
      'quan ao': 'Quần áo',
      'do choi': 'Đồ chơi',
      'khac': 'Khác',
    };
    String result = rawPattern;
    dict.forEach((key, value) {
      result = result.replaceAll(key, value);
    });
    return result;
  }

  /// [hasError] is true when the last analysis failed; it replaces the "not enough data" message, which would be wrong then.
  static Widget buildSpadeSection(
    ColorScheme colors,
    List<Map<String, dynamic>> spadePatterns,
    bool isLoading, {
    bool hasError = false,
  }) {
    // Made once for the whole list rather than once per pattern.
    final cardDecoration = BoxDecoration(
      color: colors.surface,
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: colors.primary.withValues(alpha: 0.2)),
    );
    final indexDecoration = BoxDecoration(
      color: colors.primaryContainer,
      borderRadius: BorderRadius.circular(10),
    );
    final indexStyle = TextStyle(
      color: colors.onPrimaryContainer,
      fontWeight: FontWeight.bold,
      fontSize: 13,
    );
    final detailStyle = TextStyle(
      color: colors.primary,
      fontSize: 12,
      fontStyle: FontStyle.italic,
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Text(
              'Mẫu Chi Tiêu Tuần Tự',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(width: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: colors.primaryContainer,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(
                  color: colors.primary.withValues(alpha: 0.4),
                ),
              ),
              child: Text(
                'WCM-SPADE',
                style: TextStyle(
                  color: colors.onPrimaryContainer,
                  fontSize: 11,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Text(
          'Hành vi chi tiêu tuần tự thường gặp của bạn',
          style: TextStyle(color: colors.onSurfaceVariant, fontSize: 13),
        ),
        const SizedBox(height: 16),
        if (isLoading)
          const Center(child: CircularProgressIndicator())
        else if (hasError)
          _buildSpadeMessageCard(
            colors,
            icon: Icons.cloud_off_rounded,
            title: 'Không tải được phân tích',
            message: 'Kiểm tra kết nối rồi làm mới để thử lại.',
          )
        else if (spadePatterns.isEmpty)
          _buildSpadeMessageCard(
            colors,
            icon: Icons.auto_graph_rounded,
            title: 'Chưa đủ dữ liệu',
            message: 'Thêm ít nhất 3 giao dịch để xem mẫu chi tiêu tuần tự.',
          )
        else
          ...List.generate(spadePatterns.length, (i) {
            final p = spadePatterns[i];
            return Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Container(
                padding: const EdgeInsets.all(16),
                decoration: cardDecoration,
                child: Row(
                  children: [
                    Container(
                      width: 36,
                      height: 36,
                      decoration: indexDecoration,
                      child: Center(
                        child: Text('#${i + 1}', style: indexStyle),
                      ),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            formatAiPattern(p['pattern']?.toString() ?? ''),
                            style: const TextStyle(
                              fontWeight: FontWeight.w500,
                              fontSize: 15,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            'Tần suất: ${p['count']} lần  ·  Độ hỗ trợ (W-Sup): ${p['wsupport']}',
                            style: detailStyle,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            );
          }),
      ],
    );
  }

  static Widget _buildSpadeMessageCard(
    ColorScheme colors, {
    required IconData icon,
    required String title,
    required String message,
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
              color: colors.primaryContainer,
              borderRadius: BorderRadius.circular(16),
            ),
            child: Icon(icon, color: colors.onPrimaryContainer, size: 28),
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
                  message,
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

  static Widget buildBudgetAdviceSection(
    ColorScheme colors,
    Map<String, dynamic>? budgetAdvice,
    bool isLoading,
  ) {
    if (isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (budgetAdvice == null) return const SizedBox.shrink();

    final recs = (budgetAdvice['recommendations'] as List?) ?? [];
    String summary = budgetAdvice['summary']?.toString() ?? '';

    summary = summary.replaceAll('(AI dùng Luật Khai Phá Chung) ', '');

    if (summary.isEmpty && recs.isEmpty) return const SizedBox.shrink();

    // A cut is shown in the error colour, a keep or a raise in the primary colour.
    final cutColor = colors.error;
    final keepColor = colors.primary;
    final amountsStyle = TextStyle(
      color: colors.onSurfaceVariant,
      fontSize: 13,
    );
    final reasonStyle = TextStyle(color: colors.onSurfaceVariant, fontSize: 12);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Text(
              'Gợi ý phân bổ tiền',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(width: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: Colors.amber.withValues(alpha: 0.2),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.amber.withValues(alpha: 0.4)),
              ),
              child: const Text(
                'WCM-SPADE',
                style: TextStyle(
                  color: Colors.amber,
                  fontSize: 11,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Text(
          'Đề xuất cắt/giữ từng mục để đạt mục tiêu tiết kiệm',
          style: TextStyle(color: colors.onSurfaceVariant, fontSize: 13),
        ),
        const SizedBox(height: 16),
        if (summary.isNotEmpty)
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: colors.surface,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.amber.withValues(alpha: 0.25)),
            ),
            child: Row(
              children: [
                const Icon(
                  Icons.tips_and_updates_rounded,
                  color: Colors.amber,
                  size: 24,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    summary,
                    style: const TextStyle(fontSize: 13, height: 1.4),
                  ),
                ),
              ],
            ),
          ),
        if (recs.isNotEmpty) const SizedBox(height: 12),
        ...recs.map((r) {
          final m = Map<String, dynamic>.from(r as Map);
          final isCut = ((m['delta'] as num?) ?? 0).toDouble() < 0;
          final accent = isCut ? cutColor : keepColor;
          return Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: colors.surface,
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: accent.withValues(alpha: 0.25)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        isCut
                            ? Icons.trending_down_rounded
                            : Icons.trending_up_rounded,
                        color: accent,
                        size: 22,
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          '${m['action']} · ${m['tag']}',
                          style: const TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 15,
                          ),
                        ),
                      ),
                      Text(
                        '${isCut ? '' : '+'}${_fmt(((m['delta'] as num?) ?? 0).toDouble())}',
                        style: TextStyle(
                          color: accent,
                          fontWeight: FontWeight.bold,
                          fontSize: 14,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '${_fmt(((m['current'] as num?) ?? 0).toDouble())} → ${_fmt(((m['suggested'] as num?) ?? 0).toDouble())} / tháng',
                    style: amountsStyle,
                  ),
                  if ((m['reason']?.toString() ?? '').isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(m['reason'].toString(), style: reasonStyle),
                  ],
                ],
              ),
            ),
          );
        }),
      ],
    );
  }

  static Widget buildBudgetGuardSection(
    ColorScheme colors,
    List<Map<String, dynamic>> spadePatterns,
    List<TransactionModel> transactions,
  ) {
    final now = DateTime.now();
    final recentCats = transactions
        .where((tx) => tx.isExpense && now.difference(tx.date).inDays < 7)
        .map((t) => t.category)
        .toSet();
    final spadeWarnings = <Widget>[];
    final warnedCons = <String>{};

    // Made once for all the warnings rather than once per warning.
    final warningDecoration = BoxDecoration(
      color: colors.surface,
      borderRadius: BorderRadius.circular(12),
      border: Border.all(color: colors.primary.withValues(alpha: 0.35)),
    );
    final headingStyle = TextStyle(
      color: colors.primary,
      fontSize: 12,
      fontWeight: FontWeight.bold,
    );

    for (final p in spadePatterns) {
      if (spadeWarnings.length >= 2) break;
      final parts = (p['pattern']?.toString() ?? '').split(' → ');
      if (parts.length < 2) continue;

      final antes = parts.first.split('+').map((s) => s.trim()).toList();
      final cons = parts.last.split('+').first.trim();
      final hitAnte = antes.firstWhere(
        (a) => recentCats.contains(a),
        orElse: () => '',
      );

      if (hitAnte.isEmpty ||
          cons.isEmpty ||
          cons == hitAnte ||
          !warnedCons.add(cons)) {
        continue;
      }

      String txt =
          'Thuật toán WCM-SPADE phát hiện luật [$hitAnte → $cons]: '
          'Sau khi chi tiền cho $hitAnte, bạn có xu hướng sẽ chi cho $cons trong vài ngày tới. '
          'Hãy chú ý kiểm soát hầu bao để không lạm lẹm vào khoản tiết kiệm!';

      spadeWarnings.add(
        Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: Container(
            padding: const EdgeInsets.all(14),
            decoration: warningDecoration,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  Icons.psychology_alt_rounded,
                  color: colors.primary,
                  size: 28,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Dự báo hành vi · Khai phá chuỗi',
                        style: headingStyle,
                      ),
                      const SizedBox(height: 6),
                      Text(
                        txt,
                        style: const TextStyle(fontSize: 13, height: 1.4),
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

    if (spadeWarnings.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Cảnh báo thói quen tương lai',
          style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 8),
        Text(
          'AI dự đoán các khoản chi sắp tới dựa trên lịch sử tuần tự của bạn.',
          style: TextStyle(color: colors.onSurfaceVariant, fontSize: 13),
        ),
        const SizedBox(height: 16),
        ...spadeWarnings,
      ],
    );
  }

  static Widget build503020Card(
    ColorScheme colors,
    List<TransactionModel> monthTxs,
    double monthIncome,
    double monthExpense,
  ) {
    if (monthIncome <= 0) return const SizedBox.shrink();

    double needs = monthTxs
        .where(
          (t) =>
              t.isExpense &&
              {
                'Ăn uống',
                'Di chuyển',
                'Siêu thị',
                'Sinh hoạt',
              }.contains(t.category),
        )
        .fold(0, (s, t) => s + t.amount);
    double wants = monthExpense - needs;
    final needsPct = needs / monthIncome * 100,
        wantsPct = wants / monthIncome * 100,
        savePct = (monthIncome - monthExpense) / monthIncome * 100;

    // Past its target, a share turns to the error colour; the track is the neutral container colour.
    Widget row(
      String lbl,
      double pct,
      double target,
      Color c, {
      bool atLeast = false,
    }) {
      final over = atLeast ? pct < target : pct > target;
      final color = over ? colors.error : c;
      return Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(lbl, style: const TextStyle(fontSize: 13)),
                ),
                Text(
                  '${pct.toStringAsFixed(0)}% / ${atLeast ? '≥' : '≤'}${target.toStringAsFixed(0)}%',
                  style: TextStyle(
                    color: color,
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: (pct / 100).clamp(0.0, 1.0),
                minHeight: 6,
                backgroundColor: colors.surfaceContainerHighest,
                valueColor: AlwaysStoppedAnimation(color),
              ),
            ),
          ],
        ),
      );
    }

    String advice =
        'Cơ cấu chi tiêu tháng này đạt chuẩn 50/30/20. Giữ nhịp này!';
    if (needsPct > 50 && needsPct >= wantsPct) {
      advice =
          'Nhóm thiết yếu đang chiếm ${needsPct.toStringAsFixed(0)}% thu nhập (chuẩn ≤50%). Rà lại Sinh hoạt/Siêu thị xem có khoản nào dồn được.';
    } else if (wantsPct > 30) {
      advice =
          'Nhóm mong muốn (Giải trí, Khác) chiếm ${wantsPct.toStringAsFixed(0)}% thu nhập (chuẩn ≤30%) — đây là chỗ dễ cắt nhất.';
    } else if (savePct < 20) {
      advice =
          'Bạn mới để dành được ${savePct.toStringAsFixed(0)}% thu nhập (chuẩn ≥20%). Tăng dần mỗi tháng một chút.';
    }

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.blueAccent.withValues(alpha: 0.25)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Sức khỏe chi tiêu — quy tắc 50/30/20',
            style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
          ),
          const SizedBox(height: 4),
          Text(
            'Thiết yếu: Ăn uống, Di chuyển, Siêu thị, Sinh hoạt · Mong muốn: Giải trí, Khác',
            style: TextStyle(color: colors.onSurfaceVariant, fontSize: 11),
          ),
          const SizedBox(height: 12),
          row('Thiết yếu', needsPct, 50, Colors.blueAccent),
          row('Mong muốn', wantsPct, 30, Colors.orangeAccent),
          row(
            'Tiết kiệm (phần dư)',
            savePct,
            20,
            colors.primary,
            atLeast: true,
          ),
          const SizedBox(height: 2),
          Text(
            advice,
            style: TextStyle(
              color: colors.onSurfaceVariant,
              fontSize: 12,
              height: 1.4,
            ),
          ),
        ],
      ),
    );
  }
}
