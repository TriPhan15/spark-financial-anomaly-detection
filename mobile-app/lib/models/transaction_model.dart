import 'package:flutter/material.dart';
import 'package:uuid/uuid.dart';

import 'package:finance_ai_app/models/receipt_status.dart';
import 'package:finance_ai_app/models/sync_status.dart';

class TransactionModel {
  /// Columns of the local `transactions` table that only exist for the sync state (see SqfliteHelper).
  static const String columnClientId = 'client_id';
  static const String columnSyncStatus = 'sync_status';

  /// Values of the body of POST /api/transactions/checkout that every transaction of the app sends the same way.
  static const String paymentMethodWallet = 'WALLET';
  static const String defaultLocation = 'Trong ứng dụng';

  static const Uuid _uuid = Uuid();

  /// The id on the server. Null while the server does not have the transaction: one that is still to be sent is found
  /// by its [clientId], never by a local row number, which could pass for the id of another transaction of the server.
  final String? id;
  final String title;
  final double amount;
  final DateTime date;
  final String category;
  final bool isExpense;
  final String iconName;
  final int? walletId;
  final String? imageUrl;

  /// The state of the receipt attached to the transaction. Null without a receipt, and always null offline: the local
  /// SQLite rows do not store it.
  final ReceiptStatus? receiptStatus;

  /// UUID v4 that this device gave the transaction when it was created. The server stores it and uses it to recognise
  /// the same transaction arriving twice, so sending it again never creates a duplicate. Null for a transaction read
  /// from the server, which does not send it back.
  final String? clientId;

  /// Whether the server has the transaction. Whatever comes from the server is [SyncStatus.synced]; a transaction
  /// created on this device is pending or in error until the server confirms it.
  final SyncStatus syncStatus;

  TransactionModel({
    this.id,
    required this.title,
    required this.amount,
    required this.date,
    required this.category,
    required this.isExpense,
    this.iconName = 'default',
    this.walletId,
    this.imageUrl,
    this.receiptStatus,
    this.clientId,
    this.syncStatus = SyncStatus.synced,
  });

  /// A new transaction of the user, not stored anywhere yet: it gets its [clientId] here, once, so every send of it
  /// (the first one and any resend) carries the same one.
  factory TransactionModel.draft({
    required String title,
    required String category,
    required double amount,
    required DateTime date,
    required bool isExpense,
  }) {
    return TransactionModel(
      title: title,
      category: category,
      amount: amount,
      date: date,
      isExpense: isExpense,
      iconName: category,
      clientId: _uuid.v4(),
    );
  }

  Color get color =>
      isExpense ? const Color(0xFFF43F5E) : const Color(0xFF10B981);

  IconData get icon => isExpense ? Icons.money_off : Icons.attach_money;

  /// True while the server has not confirmed the transaction: it is waiting to be sent, or its last send failed.
  bool get isUnsynced => syncStatus != SyncStatus.synced;

  /// The same transaction in another sync state.
  TransactionModel withSyncStatus(SyncStatus status) {
    return TransactionModel(
      id: id,
      title: title,
      amount: amount,
      date: date,
      category: category,
      isExpense: isExpense,
      iconName: iconName,
      walletId: walletId,
      imageUrl: imageUrl,
      receiptStatus: receiptStatus,
      clientId: clientId,
      syncStatus: status,
    );
  }

  /// The body of POST /api/transactions/checkout. It is built only from the fields that are stored locally, so a resend
  /// sends exactly what the first send did; the server answers a repeated [clientId] with the first transaction and
  /// ignores any difference in the rest.
  Map<String, dynamic> toCheckoutJson() {
    return {
      'amount': amount,
      'note': title,
      'location': title.isEmpty ? defaultLocation : title,
      'paymentMethod': paymentMethodWallet,
      'transactionDate': date.toIso8601String(),
      'categoryName': category,
      'isExpense': isExpense,
      if (clientId != null) 'clientId': clientId,
    };
  }

  Map<String, dynamic> toMap() {
    return {
      'title': title,
      'amount': amount,
      'date': date.toIso8601String(),
      'category': category,
      'isExpense': isExpense,
      'icon_name': iconName,
    };
  }

  factory TransactionModel.fromMap(Map<String, dynamic> map) {
    return TransactionModel(
      id: map['id']?.toString(),
      title: map['title'] ?? '',
      amount: (map['amount'] ?? 0).toDouble(),
      date: DateTime.parse(map['date']),
      category: map['category'] ?? 'Khác',
      isExpense: map['isExpense'] ?? true,
      iconName: map['icon_name'] ?? 'default',
    );
  }

  /// A transaction as core-backend sends it (TransactionResponseDto). Its title is already the note, or the category
  /// when there is no note.
  factory TransactionModel.fromJson(Map<String, dynamic> json) {
    return TransactionModel(
      id: json['id']?.toString(),
      title: (json['title'] ?? json['category'] ?? '').toString(),
      amount: (json['amount'] as num?)?.toDouble() ?? 0.0,
      date: DateTime.tryParse(json['date']?.toString() ?? '') ?? DateTime.now(),
      category: (json['category'] ?? 'Khác').toString(),
      isExpense: json['isExpense'] == true,
      iconName: (json['category'] ?? 'default').toString(),
      imageUrl: json['imageUrl']?.toString(),
      receiptStatus: ReceiptStatus.fromServer(json['receiptStatus']),
    );
  }

  Map<String, dynamic> toDbMap(int userId) {
    return {
      'user_id': userId,
      'title': title,
      'amount': amount,
      'date': date.toIso8601String(),
      'category': category,
      'is_expense': isExpense ? 1 : 0,
      'icon_name': iconName,
      'wallet_id': walletId,
      columnClientId: clientId,
      columnSyncStatus: syncStatus.dbValue,
    };
  }

  factory TransactionModel.fromDbMap(Map<String, dynamic> map) {
    final syncStatus = SyncStatus.fromDb(map[columnSyncStatus]);
    return TransactionModel(
      // A row the server has not confirmed has no id there; its row number must not pass for one.
      id: syncStatus == SyncStatus.synced ? map['id']?.toString() : null,
      title: map['title'] ?? '',
      amount: (map['amount'] ?? 0).toDouble(),
      date: DateTime.parse(map['date']),
      category: map['category'] ?? 'Khác',
      isExpense: map['is_expense'] == 1,
      iconName: map['icon_name'] ?? 'default',
      walletId: map['wallet_id'] as int?,
      clientId: map[columnClientId] as String?,
      syncStatus: syncStatus,
    );
  }
}
