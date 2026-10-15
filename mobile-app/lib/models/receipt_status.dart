/// How far the processing of the receipt attached to a transaction has come, reduced to the three states the app shows.
///
/// core-backend keeps the name of its own ReceiptStatus in Receipt.status (PENDING, SUCCESS, FAILED,
/// WAITING_FOR_REVIEW, CANCELED, in any letter case); the app reads it from the `receiptStatus` field of a transaction.
enum ReceiptStatus {
  /// Being read, or waiting for an administrator to check the amount the user corrected.
  processing('Đang xử lý'),

  /// Read and confirmed.
  success('Hoàn tất'),

  /// Could not be read or was rejected: the user has to confirm the amount again.
  failed('Lỗi · cần xác nhận lại');

  const ReceiptStatus(this.label);

  /// The text of the badge.
  final String label;

  static const String _serverSuccess = 'SUCCESS';
  static const String _serverFailed = 'FAILED';
  static const Set<String> _serverInProgress = {
    'PENDING',
    'WAITING_FOR_REVIEW',
    'PROCESSING',
  };

  /// The status for the value the server sent, or null when there is nothing to show: no receipt, a withdrawn one
  /// (CANCELED) or a name this version of the app does not know.
  static ReceiptStatus? fromServer(Object? raw) {
    if (raw is! String) return null;
    final name = raw.trim().toUpperCase();
    if (name == _serverSuccess) return ReceiptStatus.success;
    if (name == _serverFailed) return ReceiptStatus.failed;
    if (_serverInProgress.contains(name)) return ReceiptStatus.processing;
    return null;
  }
}
