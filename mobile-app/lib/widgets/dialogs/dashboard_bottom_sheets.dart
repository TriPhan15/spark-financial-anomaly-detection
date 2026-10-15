import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:finance_ai_app/models/wallet_model.dart';

/// The sheets take their surface colour from the theme's bottomSheetTheme; their content reads the colour scheme once
/// per sheet.
class DashboardBottomSheets {
  /// How far a bank colour moves toward the text colour in dark mode. The dark brand colours (MB, Sacombank, TPBank...)
  /// fall under 3:1 on the dark sheet; halfway, the weakest keeps about 4:1 on its tile and every bank keeps its hue.
  static const double _darkBrandLift = 0.5;

  /// [brand] as the current theme shows it: unchanged in light mode, lifted toward the text colour in dark mode.
  static Color _brandColor(ColorScheme colors, Color brand) =>
      colors.brightness == Brightness.dark
      ? Color.lerp(brand, colors.onSurface, _darkBrandLift)!
      : brand;

  static void showBankConnect(BuildContext context) {
    const banks = [
      (Icons.account_balance_rounded, 'Vietcombank', 'VCB', Color(0xFF007236)),
      (Icons.account_balance_rounded, 'VietinBank', 'CTG', Color(0xFF0066B3)),
      (Icons.account_balance_rounded, 'BIDV', 'BIDV', Color(0xFF0079B9)),
      (Icons.account_balance_rounded, 'Agribank', 'AGB', Color(0xFF028A3D)),
      (Icons.account_balance_rounded, 'Techcombank', 'TCB', Color(0xFFE30613)),
      (Icons.account_balance_rounded, 'MB Bank', 'MBB', Color(0xFF001D7E)),
      (Icons.account_balance_rounded, 'ACB', 'ACB', Color(0xFF0055A5)),
      (Icons.account_balance_rounded, 'VPBank', 'VPB', Color(0xFF006633)),
      (Icons.account_balance_rounded, 'TPBank', 'TPB', Color(0xFF8B1A1A)),
      (Icons.account_balance_rounded, 'Sacombank', 'STB', Color(0xFF003DA5)),
    ];
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) {
        final colors = Theme.of(ctx).colorScheme;
        final dividerColor = colors.onSurfaceVariant.withValues(alpha: 0.08);
        final codeStyle = TextStyle(
          color: colors.onSurfaceVariant,
          fontSize: 12,
        );
        final actionFill = colors.onSurfaceVariant.withValues(alpha: 0.1);
        final actionStyle = TextStyle(
          color: colors.onSurfaceVariant,
          fontSize: 11,
        );
        return SafeArea(
          child: DraggableScrollableSheet(
            expand: false,
            initialChildSize: 0.55,
            maxChildSize: 0.85,
            builder: (_, controller) => Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 20, 24, 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Kết nối ngân hàng',
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Liên kết tài khoản ngân hàng để đồng bộ giao dịch',
                        style: TextStyle(
                          color: colors.onSurfaceVariant,
                          fontSize: 13,
                        ),
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: ListView.separated(
                    controller: controller,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 4,
                    ),
                    itemCount: banks.length,
                    separatorBuilder: (_, _) => Divider(
                      height: 1,
                      indent: 60,
                      endIndent: 16,
                      color: dividerColor,
                    ),
                    itemBuilder: (_, i) {
                      final b = banks[i];
                      final brand = _brandColor(colors, b.$4);
                      return ListTile(
                        leading: Container(
                          padding: const EdgeInsets.all(8),
                          decoration: BoxDecoration(
                            color: brand.withValues(alpha: 0.15),
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Icon(b.$1, color: brand, size: 20),
                        ),
                        title: Text(
                          b.$2,
                          style: const TextStyle(fontWeight: FontWeight.w500),
                        ),
                        subtitle: Text(b.$3, style: codeStyle),
                        trailing: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 10,
                            vertical: 4,
                          ),
                          decoration: BoxDecoration(
                            color: actionFill,
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: Text('Kết nối', style: actionStyle),
                        ),
                        onTap: () {
                          Navigator.pop(ctx);
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(
                              content: Text('Tính năng sắp ra mắt'),
                            ),
                          );
                        },
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  static void showBankSources(BuildContext context, List<WalletModel> wallets) {
    final fmtCur = NumberFormat.currency(
      locale: 'vi_VN',
      symbol: '₫',
      decimalDigits: 0,
    );
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) {
        final colors = Theme.of(ctx).colorScheme;
        final balanceStyle = TextStyle(
          color: colors.primary,
          fontWeight: FontWeight.bold,
        );
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Nguồn tiền',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 4),
                Text(
                  'Các ví đã liên kết với tài khoản',
                  style: TextStyle(
                    color: colors.onSurfaceVariant,
                    fontSize: 13,
                  ),
                ),
                const SizedBox(height: 16),
                if (wallets.isEmpty)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    child: Text(
                      'Chưa có ví nào.',
                      style: TextStyle(color: colors.onSurfaceVariant),
                    ),
                  )
                else
                  ...wallets.map(
                    (w) => ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Container(
                        padding: const EdgeInsets.all(8),
                        decoration: BoxDecoration(
                          color: Color(w.colorValue).withValues(alpha: 0.15),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Icon(
                          Icons.account_balance_wallet_rounded,
                          color: Color(w.colorValue),
                          size: 20,
                        ),
                      ),
                      title: Text(
                        w.name,
                        style: const TextStyle(fontWeight: FontWeight.w500),
                      ),
                      trailing: Text(
                        fmtCur.format(w.balance),
                        style: balanceStyle,
                      ),
                    ),
                  ),
                const SizedBox(height: 8),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    onPressed: () {
                      Navigator.pop(ctx);
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('Tính năng sắp ra mắt')),
                      );
                    },
                    icon: Icon(Icons.add_rounded, color: colors.primary),
                    label: Text(
                      'Thêm nguồn tiền',
                      style: TextStyle(color: colors.primary),
                    ),
                    style: OutlinedButton.styleFrom(
                      side: BorderSide(
                        color: colors.primary.withValues(alpha: 0.4),
                      ),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  static void showAllFeatures(
    BuildContext context, {

    required VoidCallback onSavings,
    required VoidCallback onBankSources,
    required VoidCallback onLinkedServices,
  }) {
    final features = [
      (
        Icons.savings_rounded,
        'Sổ tiết kiệm',
        'Quản lý mục tiêu tiết kiệm',
        onSavings,
      ),
      (
        Icons.account_balance_wallet_rounded,
        'Nguồn tiền',
        'Xem ví và số dư',
        onBankSources,
      ),
      (
        Icons.link_rounded,
        'Dịch vụ liên kết',
        'MoMo, ZaloPay, ngân hàng...',
        onLinkedServices,
      ),
    ];

    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) {
        final colors = Theme.of(ctx).colorScheme;
        final descriptionStyle = TextStyle(
          color: colors.onSurfaceVariant,
          fontSize: 10,
          height: 1.2,
        );
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Tất cả tính năng',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 16),
                GridView.count(
                  shrinkWrap: true,
                  crossAxisCount: 4,
                  crossAxisSpacing: 12,
                  mainAxisSpacing: 16,
                  childAspectRatio: 0.53,
                  physics: const NeverScrollableScrollPhysics(),
                  children: features.map((f) {
                    return GestureDetector(
                      onTap: f.$4,
                      child: Column(
                        children: [
                          Container(
                            width: 56,
                            height: 56,
                            decoration: BoxDecoration(
                              color: colors.primaryContainer,
                              borderRadius: BorderRadius.circular(16),
                            ),
                            child: Icon(
                              f.$1,
                              color: colors.onPrimaryContainer,
                              size: 26,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            f.$2,
                            textAlign: TextAlign.center,
                            style: const TextStyle(
                              fontSize: 11,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            f.$3,
                            textAlign: TextAlign.center,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: descriptionStyle,
                          ),
                        ],
                      ),
                    );
                  }).toList(),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
