import 'package:finance_ai_app/models/sync_status.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/services/sqflite_helper.dart';

/// Stands in for the SQLite table of the transactions of this device, so that no test opens a real database: its calls
/// never complete inside the fake async of `testWidgets`. Install it with `AppRepository.instance.localStore = ...`.
///
/// It answers only what AppRepository asks of the local store and fails loudly on anything else.
class FakeLocalStore implements SqfliteHelper {
  final List<({int userId, TransactionModel transaction})> _rows = [];

  /// Every row of every user in the order it was inserted, with the sync state it has now.
  List<TransactionModel> get rows => [for (final row in _rows) row.transaction];

  /// The row with this clientId, or null when there is none.
  TransactionModel? byClientId(String clientId) {
    for (final row in _rows) {
      if (row.transaction.clientId == clientId) return row.transaction;
    }
    return null;
  }

  void clear() => _rows.clear();

  @override
  Future<int> insertTransaction(
    TransactionModel transaction,
    int userId,
  ) async {
    _rows.add((userId: userId, transaction: transaction));
    return _rows.length;
  }

  @override
  Future<List<TransactionModel>> getUnsyncedTransactions(int userId) async {
    return [
      for (final row in _rows)
        if (row.userId == userId && row.transaction.isUnsynced) row.transaction,
    ];
  }

  @override
  Future<int> updateTransactionSyncStatus(
    int userId,
    String clientId,
    SyncStatus status,
  ) async {
    var changed = 0;
    for (var i = 0; i < _rows.length; i++) {
      final row = _rows[i];
      if (row.userId == userId && row.transaction.clientId == clientId) {
        _rows[i] = (
          userId: row.userId,
          transaction: row.transaction.withSyncStatus(status),
        );
        changed++;
      }
    }
    return changed;
  }

  @override
  Future<int> deleteTransactionByClientId(int userId, String clientId) async {
    final before = _rows.length;
    _rows.removeWhere(
      (row) => row.userId == userId && row.transaction.clientId == clientId,
    );
    return before - _rows.length;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) {
    throw UnimplementedError(
      'FakeLocalStore does not fake ${invocation.memberName}',
    );
  }
}
