/// Whether core-backend has confirmed a transaction that was created on this device.
///
/// Everything the app reads from the server is [synced]. Only a new transaction goes through the other two states, and it
/// is kept in the local SQLite table for as long as it is not [synced], so that it can be sent again.
enum SyncStatus {
  /// Saved on the device but not confirmed by the server yet: it is being sent, or the app was closed before the answer
  /// came. Either way the user can send it again.
  pending('pending', 'Chờ gửi'),

  /// The server has the transaction.
  synced('synced', 'Đã đồng bộ'),

  /// The last send failed (no network, timeout, server error) and the user can send it again.
  error('error', 'Gửi lỗi');

  const SyncStatus(this.dbValue, this.label);

  /// The value stored in the `sync_status` column, kept apart from the enum name so that renaming a constant cannot
  /// orphan the rows already on a device.
  final String dbValue;

  /// The text of the badge.
  final String label;

  /// The status for a stored value. A row without one (created before the column existed) or with a value this version
  /// does not know was never waiting to be sent, so it counts as [synced].
  static SyncStatus fromDb(Object? raw) {
    for (final status in values) {
      if (status.dbValue == raw) return status;
    }
    return SyncStatus.synced;
  }
}
