import 'package:flutter/foundation.dart';

/// What a notification is about, as core-backend names it in `type`. A name this version of the app does not know
/// becomes [unknown], so a type added on the server never breaks the list.
enum NotificationType {
  budgetWarning('BUDGET_WARNING'),
  budgetExceeded('BUDGET_EXCEEDED'),
  transactionAlert('TRANSACTION_ALERT'),
  receiptProcessed('RECEIPT_PROCESSED'),
  unknown(null);

  const NotificationType(this.serverName);

  /// The name in the JSON; null for [unknown], which the server never sends.
  final String? serverName;

  /// Any letter case; a missing, empty or unknown value is [unknown].
  static NotificationType fromServer(Object? value) {
    if (value is! String) return unknown;
    final name = value.trim().toUpperCase();
    for (final type in values) {
      if (type.serverName == name) return type;
    }
    return unknown;
  }
}

/// One notification of GET /api/notifications, with every field typed: the JSON map never travels further than
/// [AppNotification.fromJson].
@immutable
class AppNotification {
  const AppNotification({
    required this.id,
    required this.type,
    required this.message,
    required this.isRead,
    this.createdAt,
    this.transactionId,
    this.budgetId,
    this.receiptId,
  });

  final int id;
  final NotificationType type;

  /// Ready to show, written by the server (in Vietnamese).
  final String message;
  final bool isRead;
  final DateTime? createdAt;

  /// What the notification is about. Any of them may be missing, and the record behind one may have been deleted.
  final int? transactionId;
  final int? budgetId;
  final int? receiptId;

  /// Throws a [FormatException] only when the id is missing or not a whole number, because such a notification could
  /// never be marked as read. Every other field that is missing or malformed gets a safe value: `isRead` null (or
  /// anything but true) is unread, an unknown `type` is [NotificationType.unknown], a bad date or id is null.
  factory AppNotification.fromJson(Map<String, dynamic> json) {
    final id = _wholeNumberOrNull(json['id']);
    if (id == null) {
      throw FormatException('A notification without a usable id', json['id']);
    }
    final createdAt = json['createdAt'];
    return AppNotification(
      id: id,
      type: NotificationType.fromServer(json['type']),
      message: json['message']?.toString() ?? '',
      isRead: _isTrue(json['isRead']),
      createdAt: createdAt is String
          ? DateTime.tryParse(createdAt)?.toLocal()
          : null,
      transactionId: _wholeNumberOrNull(json['transactionId']),
      budgetId: _wholeNumberOrNull(json['budgetId']),
      receiptId: _wholeNumberOrNull(json['receiptId']),
    );
  }

  AppNotification copyWith({bool? isRead}) => AppNotification(
    id: id,
    type: type,
    message: message,
    isRead: isRead ?? this.isRead,
    createdAt: createdAt,
    transactionId: transactionId,
    budgetId: budgetId,
    receiptId: receiptId,
  );

  @override
  bool operator ==(Object other) =>
      other is AppNotification &&
      other.id == id &&
      other.type == type &&
      other.message == message &&
      other.isRead == isRead &&
      other.createdAt == createdAt &&
      other.transactionId == transactionId &&
      other.budgetId == budgetId &&
      other.receiptId == receiptId;

  @override
  int get hashCode => Object.hash(
    id,
    type,
    message,
    isRead,
    createdAt,
    transactionId,
    budgetId,
    receiptId,
  );

  @override
  String toString() =>
      'AppNotification($id, ${type.name}, ${isRead ? 'read' : 'unread'})';
}

/// One page of GET /api/notifications?page=&size=, newest first.
@immutable
class NotificationPage {
  const NotificationPage({
    required this.content,
    required this.page,
    required this.size,
    required this.totalElements,
    required this.totalPages,
    required this.hasNext,
  });

  final List<AppNotification> content;

  /// Zero-based, as sent.
  final int page;
  final int size;
  final int totalElements;
  final int totalPages;
  final bool hasNext;

  /// Throws a [FormatException] when `content` is not a list, since then the answer is not a page at all. A single
  /// notification that cannot be read (not an object, no usable id) is left out and logged, so it never hides the rest.
  factory NotificationPage.fromJson(Map<String, dynamic> json) {
    final rawContent = json['content'];
    if (rawContent is! List) {
      throw FormatException(
        'A notification page without a content list',
        rawContent,
      );
    }
    final content = <AppNotification>[];
    for (final item in rawContent) {
      if (item is! Map) {
        debugPrint('Notification skipped: not a JSON object ($item).');
        continue;
      }
      try {
        content.add(AppNotification.fromJson(Map<String, dynamic>.from(item)));
      } on FormatException catch (e) {
        debugPrint('Notification skipped: ${e.message} (${e.source}).');
      }
    }
    return NotificationPage(
      content: List.unmodifiable(content),
      page: _wholeNumberOrNull(json['page']) ?? 0,
      size: _wholeNumberOrNull(json['size']) ?? content.length,
      totalElements:
          _wholeNumberOrNull(json['totalElements']) ?? content.length,
      totalPages: _wholeNumberOrNull(json['totalPages']) ?? 0,
      hasNext: _isTrue(json['hasNext']),
    );
  }
}

/// true, "true" (any case) or a non-zero number; everything else, null included, is false.
bool _isTrue(Object? value) => switch (value) {
  final bool flag => flag,
  final String text => text.trim().toLowerCase() == 'true',
  final num number => number != 0,
  _ => false,
};

/// An int, a whole double (7.0) or a numeric string; anything else is null.
int? _wholeNumberOrNull(Object? value) => switch (value) {
  final int number => number,
  final double number when number.isFinite && number == number.truncate() =>
    number.toInt(),
  final String text => int.tryParse(text.trim()),
  _ => null,
};
