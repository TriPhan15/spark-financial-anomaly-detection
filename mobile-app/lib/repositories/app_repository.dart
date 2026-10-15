import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/models/budget_model.dart';
import 'package:finance_ai_app/models/category_model.dart';
import 'package:finance_ai_app/models/sync_status.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/models/user_model.dart';
import 'package:finance_ai_app/models/wallet_model.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/sqflite_helper.dart';

/// The server understood a transaction and refused it (a category that does not exist, a balance that is too low ...),
/// so sending it again cannot help until the data changes. [message] is the reason, ready to show to the user.
class TransactionRefusedException implements Exception {
  const TransactionRefusedException(this.message);

  final String message;

  @override
  String toString() => 'TransactionRefusedException: $message';
}

class AppRepository {
  static final AppRepository instance = AppRepository._();
  AppRepository._();

  /// How the backend labels expense categories in GET /api/categories.
  static const String _serverExpenseType = 'EXPENSE';

  /// 4xx answers that say nothing about the transaction itself (the session ran out, the server timed out or is busy),
  /// so the transaction is kept and can be sent again. Every other 4xx is a refusal of the transaction.
  static const Set<int> _retryableClientErrors = {
    HttpStatus.unauthorized,
    HttpStatus.requestTimeout,
    HttpStatus.tooManyRequests,
  };

  static const String _defaultRefusalMessage =
      'Máy chủ từ chối giao dịch này. Vui lòng kiểm tra lại thông tin.';

  /// Where the transactions that the server has not confirmed are kept; only tests replace it.
  SqfliteHelper _localStore = SqfliteHelper.instance;

  @visibleForTesting
  set localStore(SqfliteHelper store) => _localStore = store;

  /// In full mode: the transactions of the server plus the ones created on this device that it has not confirmed yet
  /// (see [getUnsyncedTransactions]), those first because they need the attention of the user. Throws when the server
  /// cannot be reached.
  Future<List<TransactionModel>> getTransactions(int userId) async {
    if (!AppConfig.isFull) {
      return SqfliteHelper.instance.getTransactionsByUserId(userId);
    }
    final onServer = await RemoteApiHelper.instance.fetchTransactions();
    return [...await getUnsyncedTransactions(userId), ...onServer];
  }

  /// The transactions created on this device that the server has not confirmed: waiting to be sent, or failed. Always
  /// empty without a server (there every transaction is local and counts as synced).
  Future<List<TransactionModel>> getUnsyncedTransactions(int userId) async {
    if (!AppConfig.isFull) return const [];
    return _localStore.getUnsyncedTransactions(userId);
  }

  Future<UserModel?> getCurrentUser(int userId) async {
    if (AppConfig.isFull) {
      final me = await RemoteApiHelper.instance.fetchMe();
      final email = (me['email'] ?? '').toString();
      return UserModel(
        id: (me['id'] as num?)?.toInt(),
        email: email,
        password: '',
        fullName: email.isNotEmpty ? email.split('@').first : 'Người dùng',
        phone: me['phone']?.toString(),
      );
    }
    return SqfliteHelper.instance.getUserById(userId);
  }

  Future<List<WalletModel>> getWallets(int userId) async {
    final txs = await getTransactions(userId);
    final bal = txs.fold<double>(
      0.0,
      (s, t) => s + (t.isExpense ? -t.amount : t.amount),
    );
    return [WalletModel(name: 'Ví chính', balance: bal, userId: userId)];
  }

  Future<Map<String, dynamic>> getHomeSummary(int userId) async {
    final results = await Future.wait([
      getCurrentUser(userId),
      getTransactions(userId),
    ]);

    final user = results[0] as UserModel?;
    final txs = results[1] as List<TransactionModel>;

    final bal = txs.fold<double>(
      0.0,
      (s, t) => s + (t.isExpense ? -t.amount : t.amount),
    );
    final wallets = [
      WalletModel(name: 'Ví chính', balance: bal, userId: userId),
    ];

    return {'user': user, 'transactions': txs, 'wallets': wallets};
  }

  Future<void> addTransaction({
    required int userId,
    required double amount,
    required bool isExpense,
    required String category,
    String? note,
  }) async {
    if (AppConfig.isFull) {
      await RemoteApiHelper.instance.addTransaction({
        'amount': amount,
        'isExpense': isExpense,
        'categoryName': category,
        'location': (note != null && note.isNotEmpty) ? note : category,
        'paymentMethod': 'Ví chính',
        'transactionDate': DateTime.now().toIso8601String(),
        'note': ?note,
      });
    } else {
      final tx = TransactionModel.draft(
        title: (note != null && note.isNotEmpty) ? note : category,
        category: category,
        amount: amount,
        date: DateTime.now(),
        isExpense: isExpense,
      );
      await SqfliteHelper.instance.insertTransaction(tx, userId);
    }
  }

  /// Saves a new transaction made on this device and returns where it ended up.
  ///
  /// In full mode it is first written to the local table as pending, so that it survives a crash or a lost connection,
  /// and then sent to the server together with its clientId. The result is [SyncStatus.synced] once the server has it, or
  /// [SyncStatus.error] when it could not be sent for a reason that sending again can fix (no network, timeout, server
  /// error): it stays on the device, flagged, until the user resends it. Throws [TransactionRefusedException] when the
  /// server refuses it, and keeps nothing then, because the user still has the form to correct. Without a server the row is
  /// simply stored.
  Future<SyncStatus> createTransaction(
    int userId,
    TransactionModel draft,
  ) async {
    if (!AppConfig.isFull) {
      await _localStore.insertTransaction(draft, userId);
      return SyncStatus.synced;
    }
    final clientId = _clientIdOf(draft);
    await _localStore.insertTransaction(
      draft.withSyncStatus(SyncStatus.pending),
      userId,
    );
    try {
      return await _send(userId, draft);
    } on TransactionRefusedException {
      await _localStore.deleteTransactionByClientId(userId, clientId);
      rethrow;
    }
  }

  /// Sends a transaction that the server has not confirmed again, with the same clientId: when the first send did arrive
  /// and only the answer got lost, the server answers with the transaction it already has instead of creating a second
  /// one. The result is that of [createTransaction], except that a refusal keeps the transaction (flagged error), so the
  /// user can still delete it.
  Future<SyncStatus> resendTransaction(
    int userId,
    TransactionModel transaction,
  ) async {
    await _localStore.updateTransactionSyncStatus(
      userId,
      _clientIdOf(transaction),
      SyncStatus.pending,
    );
    return _send(userId, transaction);
  }

  /// Throws away, for good, a transaction that the server never confirmed.
  Future<void> deleteUnsyncedTransaction(int userId, String clientId) async {
    await _localStore.deleteTransactionByClientId(userId, clientId);
  }

  /// One attempt to send a transaction that is already in the local table, after which its flag says how it went. Only a
  /// refusal is thrown; any other failure of the request is an outcome ([SyncStatus.error]), not an exception.
  Future<SyncStatus> _send(int userId, TransactionModel transaction) async {
    final clientId = _clientIdOf(transaction);
    try {
      await RemoteApiHelper.instance.addTransaction(
        transaction.toCheckoutJson(),
      );
    } on DioException catch (e) {
      debugPrint('Lỗi gửi giao dịch $clientId: $e');
      await _localStore.updateTransactionSyncStatus(
        userId,
        clientId,
        SyncStatus.error,
      );
      if (_isRefusal(e)) throw TransactionRefusedException(_refusalMessage(e));
      return SyncStatus.error;
    }
    await _localStore.updateTransactionSyncStatus(
      userId,
      clientId,
      SyncStatus.synced,
    );
    return SyncStatus.synced;
  }

  static String _clientIdOf(TransactionModel transaction) {
    return transaction.clientId ??
        (throw ArgumentError.value(
          transaction,
          'transaction',
          'has no clientId, so it was not created on this device',
        ));
  }

  /// True when the server answered and said no to the transaction. No answer at all (no network, a timeout) and a server
  /// error are not a refusal: the transaction may still be accepted later.
  static bool _isRefusal(DioException e) {
    final status = e.response?.statusCode;
    if (status == null) return false;
    return status >= HttpStatus.badRequest &&
        status < HttpStatus.internalServerError &&
        !_retryableClientErrors.contains(status);
  }

  static String _refusalMessage(DioException e) {
    final data = e.response?.data;
    final message = (data is Map ? data['message'] : null)?.toString().trim();
    return (message == null || message.isEmpty)
        ? _defaultRefusalMessage
        : message;
  }

  /// All the budgets of the user, newest first: from the server, or from the local table (which only stores monthly
  /// budgets). Callers pick the ones of a period themselves, see BudgetModel.coversDay and BudgetModel.overlaps.
  Future<List<BudgetModel>> getBudgets(int userId) {
    if (AppConfig.isFull) return RemoteApiHelper.instance.getBudgets();
    return SqfliteHelper.instance.getBudgetsByUserId(userId);
  }

  /// Like [getBudgets] for screens that show budgets next to other data: a failure comes back as null, so the rest of
  /// the screen still loads and can say that the budgets are missing.
  Future<List<BudgetModel>?> tryGetBudgets(int userId) async {
    try {
      return await getBudgets(userId);
    } catch (e) {
      debugPrint('Lỗi tải ngân sách: $e');
      return null;
    }
  }

  /// Creates the budget, or updates it when it already has an id, and returns the stored copy.
  Future<BudgetModel> saveBudget(int userId, BudgetModel budget) async {
    if (AppConfig.isFull) {
      return budget.id == null
          ? RemoteApiHelper.instance.createBudget(budget)
          : RemoteApiHelper.instance.updateBudget(budget);
    }
    final local = budget.copyWith(userId: userId);
    if (local.id == null) {
      final id = await SqfliteHelper.instance.insertBudget(local);
      return local.copyWith(id: id);
    }
    await SqfliteHelper.instance.updateBudget(local);
    return local;
  }

  Future<void> deleteBudget(int budgetId) async {
    if (AppConfig.isFull) {
      await RemoteApiHelper.instance.deleteBudget(budgetId);
    } else {
      await SqfliteHelper.instance.deleteBudget(budgetId);
    }
  }

  /// The expense categories a budget can be limited to, from the server or from the local table; empty when none load.
  Future<List<BudgetCategory>> getExpenseCategories(int userId) async {
    if (AppConfig.isFull) {
      final categories = await RemoteApiHelper.instance.fetchCategories();
      return [
        for (final category in categories)
          if (category['type'] == _serverExpenseType)
            (
              id: (category['id'] as num).toInt(),
              name: category['name'].toString(),
            ),
      ];
    }
    final categories = await SqfliteHelper.instance.getCategoriesByUserId(
      userId,
    );
    return [
      for (final category in categories)
        if (category.type == CategoryType.expense && category.id != null)
          (id: category.id!, name: category.name),
    ];
  }
}
