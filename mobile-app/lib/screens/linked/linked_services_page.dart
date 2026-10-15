import 'dart:async';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/screens/payment/payment_history_screen.dart';

class LinkedServicesPage extends StatefulWidget {
  const LinkedServicesPage({super.key});

  @override
  State<LinkedServicesPage> createState() => _LinkedServicesPageState();
}

class _LinkedServicesPageState extends State<LinkedServicesPage> {
  static const _wallets = [
    ('VNPAY', 'VNPAY'),
    ('ZALOPAY', 'ZaloPay'),
    ('MOCK', 'Ví Demo'),
  ];

  bool _syncing = false;
  Timer? _poll;

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  String _fmt(num v) => NumberFormat.currency(
    locale: 'vi_VN',
    symbol: '₫',
    decimalDigits: 0,
  ).format(v);

  void _snack(String m) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(m)));

  /// The provider logo is an image and keeps its own colours; only the fallback icon takes the theme.
  Widget _logo(ColorScheme colors, String provider, {double size = 32}) {
    final fallback =
        {
          'VNPAY': Icons.account_balance_rounded,
          'ZALOPAY': Icons.account_balance_wallet_rounded,
          'MOCK': Icons.wallet_rounded,
        }[provider] ??
        Icons.payment_rounded;
    return Image.asset(
      'assets/payment/${provider.toLowerCase()}.png',
      width: size,
      height: size,
      errorBuilder: (_, _, _) =>
          Icon(fallback, color: colors.primary, size: size),
    );
  }

  Future<void> _openSync(String provider, String label) async {
    if (!AppConfig.isFull) {
      _snack('Đồng bộ ví cần chế độ FULL (backend).');
      return;
    }
    final ctrl = TextEditingController();
    final amount = await showDialog<double>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Đồng bộ từ $label'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Số tiền nạp vào ngân sách chi tiêu:',
              style: TextStyle(
                color: Theme.of(ctx).colorScheme.onSurfaceVariant,
                fontSize: 13,
              ),
            ),
            const SizedBox(height: 8),
            // Text and hint take the theme: onSurface and onSurfaceVariant.
            TextField(
              controller: ctrl,
              keyboardType: TextInputType.number,
              autofocus: true,
              decoration: const InputDecoration(hintText: 'VD: 100000'),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Hủy'),
          ),
          FilledButton(
            onPressed: () {
              final a = double.tryParse(
                ctrl.text.trim().replaceAll(RegExp(r'[^\d]'), ''),
              );
              Navigator.pop(ctx, a);
            },
            child: const Text('Đồng bộ'),
          ),
        ],
      ),
    );
    if (amount == null) return;
    if (amount < 1000) {
      _snack('Số tiền tối thiểu 1.000₫.');
      return;
    }
    await _runSync(provider, amount);
  }

  Future<void> _runSync(String provider, double amount) async {
    setState(() => _syncing = true);
    try {
      final order = await RemoteApiHelper.instance.createPayment(
        type: 'DEPOSIT',
        amount: amount,
        provider: provider,
      );
      final orderCode = order['orderCode'] as String;
      final payUrl = order['payUrl'] as String;
      final uri = Uri.parse(payUrl);
      if (await canLaunchUrl(uri)) {
        await launchUrl(uri, mode: LaunchMode.externalApplication);
      }
      _pollSync(orderCode, amount);
    } catch (e) {
      if (mounted) {
        _snack('Tạo đơn đồng bộ lỗi: $e');
        setState(() => _syncing = false);
      }
    }
  }

  void _pollSync(String orderCode, double amount) {
    _poll?.cancel();
    int tries = 0;
    _poll = Timer.periodic(const Duration(seconds: 2), (t) async {
      tries++;
      try {
        final st = await RemoteApiHelper.instance.paymentStatus(orderCode);
        final status = st['status'];
        if (status != 'PENDING') {
          t.cancel();
          if (!mounted) return;
          setState(() => _syncing = false);
          if (status == 'SUCCESS') {
            _snack('Đã đồng bộ ${_fmt(amount)} vào ngân sách chi tiêu.');
            if (mounted) Navigator.pop(context, true);
          } else {
            _snack('Đồng bộ: $status');
          }
        }
      } catch (_) {}
      if (tries >= 60) {
        t.cancel();
        if (mounted) setState(() => _syncing = false);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: colors.surface,
      // The icons take the app bar theme (onSurface).
      appBar: AppBar(
        backgroundColor: colors.surface,
        title: const Text(
          'Dịch vụ liên kết',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        actions: [
          IconButton(
            tooltip: 'Lịch sử đồng bộ',
            icon: const Icon(Icons.history_rounded),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const PaymentHistoryScreen()),
            ),
          ),
        ],
      ),
      body: AbsorbPointer(
        absorbing: _syncing,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const Text(
              'Ví điện tử đã liên kết',
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 4),
            Text(
              'Đồng bộ tiền từ ví vào ngân sách để quản lý chi tiêu.',
              style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
            ),
            const SizedBox(height: 16),
            ..._wallets.map((w) => _walletTile(colors, w.$1, w.$2)),
            if (_syncing) ...[
              const SizedBox(height: 20),
              Center(
                child: Column(
                  children: [
                    const CircularProgressIndicator(),
                    const SizedBox(height: 8),
                    Text(
                      'Đang đồng bộ... hoàn tất thanh toán trên cổng.',
                      style: TextStyle(
                        color: colors.onSurfaceVariant,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _walletTile(ColorScheme colors, String provider, String label) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          _logo(colors, provider, size: 36),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 2),
                // Green "linked" status: readable on both themes (6.1:1 on the dark surface).
                Row(
                  children: const [
                    Icon(
                      Icons.check_circle_rounded,
                      color: Colors.green,
                      size: 13,
                    ),
                    SizedBox(width: 4),
                    Text(
                      'Đã liên kết',
                      style: TextStyle(color: Colors.green, fontSize: 11),
                    ),
                  ],
                ),
              ],
            ),
          ),
          FilledButton.icon(
            onPressed: () => _openSync(provider, label),
            icon: const Icon(Icons.sync_rounded, size: 18),
            label: const Text('Đồng bộ'),
          ),
        ],
      ),
    );
  }
}
