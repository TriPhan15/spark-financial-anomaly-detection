class ReceiptModel {
  final String sellerName;
  final String address;
  final String timestamp;
  final String totalCost;
  final double? parsedAmount;

  const ReceiptModel({
    required this.sellerName,
    required this.address,
    required this.timestamp,
    required this.totalCost,
    this.parsedAmount,
  });

  factory ReceiptModel.fromApi(Map<String, dynamic> json) {
    final seller = (json['seller'] is Map)
        ? json['seller'] as Map<String, dynamic>
        : json;
    final costStr = (seller['total_cost'] ?? '0').toString();
    final parsed = double.tryParse(costStr.replaceAll(RegExp(r'[^\d.]'), ''));
    return ReceiptModel(
      sellerName: seller['seller_name']?.toString() ?? '',
      address: seller['address']?.toString() ?? '',
      timestamp: seller['timestamp']?.toString() ?? '',
      totalCost: costStr,
      parsedAmount: parsed,
    );
  }

  factory ReceiptModel.mock() => const ReceiptModel(
    sellerName: 'Siêu thị VinMart',
    address: '123 Nguyễn Trãi, Q.1, TP.HCM',
    timestamp: '2026-05-27 10:30',
    totalCost: '245000',
    parsedAmount: 245000,
  );
}
