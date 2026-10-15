import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/models/app_notification.dart';

import '../helpers/captured_logs.dart';

/// A notification as core-backend sends it; [overrides] replaces or adds keys, and a key mapped to [_absent] is removed.
Map<String, dynamic> _json([Map<String, Object?> overrides = const {}]) {
  final json = <String, dynamic>{
    'id': 7,
    'type': 'BUDGET_WARNING',
    'message':
        'Bạn đã chi 1.600.000 / 2.000.000 VNĐ, đạt 80% ngân sách Ăn uống.',
    'isRead': false,
    'createdAt': '2026-09-21T10:15:30',
    'transactionId': 500,
    'budgetId': 100,
    'receiptId': null,
  };
  for (final MapEntry(:key, :value) in overrides.entries) {
    if (identical(value, _absent)) {
      json.remove(key);
    } else {
      json[key] = value;
    }
  }
  return json;
}

const Object _absent = Object();

void main() {
  group('AppNotification.fromJson', () {
    test('reads every field of the contract', () {
      final notification = AppNotification.fromJson(_json());

      expect(notification.id, 7);
      expect(notification.type, NotificationType.budgetWarning);
      expect(
        notification.message,
        'Bạn đã chi 1.600.000 / 2.000.000 VNĐ, đạt 80% ngân sách Ăn uống.',
      );
      expect(notification.isRead, isFalse);
      expect(notification.createdAt, DateTime(2026, 9, 21, 10, 15, 30));
      expect(notification.transactionId, 500);
      expect(notification.budgetId, 100);
      expect(notification.receiptId, isNull);
    });

    group('isRead', () {
      final cases = <Object?, bool>{
        true: true,
        false: false,
        null: false,
        'true': true,
        'TRUE': true,
        'false': false,
        'yes': false,
        1: true,
        0: false,
      };
      for (final MapEntry(key: value, value: expected) in cases.entries) {
        test('${value is String ? '"$value"' : value} reads as $expected', () {
          expect(
            AppNotification.fromJson(_json({'isRead': value})).isRead,
            expected,
          );
        });
      }

      test('a missing key reads as unread', () {
        expect(
          AppNotification.fromJson(_json({'isRead': _absent})).isRead,
          isFalse,
        );
      });
    });

    group('type', () {
      test('maps every server name, in any letter case', () {
        expect(
          AppNotification.fromJson(_json({'type': 'BUDGET_EXCEEDED'})).type,
          NotificationType.budgetExceeded,
        );
        expect(
          AppNotification.fromJson(_json({'type': 'transaction_alert'})).type,
          NotificationType.transactionAlert,
        );
        expect(
          AppNotification.fromJson(_json({'type': ' Receipt_Processed '})).type,
          NotificationType.receiptProcessed,
        );
      });

      for (final value in <Object?>['SAVING_GOAL_REACHED', '', null, 42]) {
        test('$value falls back to unknown instead of failing', () {
          expect(
            AppNotification.fromJson(_json({'type': value})).type,
            NotificationType.unknown,
          );
        });
      }

      test('a missing key falls back to unknown too', () {
        expect(
          AppNotification.fromJson(_json({'type': _absent})).type,
          NotificationType.unknown,
        );
      });

      test('unknown matches no server name, even an empty one', () {
        expect(NotificationType.unknown.serverName, isNull);
        expect(NotificationType.fromServer(null), NotificationType.unknown);
      });
    });

    group('payload', () {
      test('null or missing ids are null, never an error', () {
        final notification = AppNotification.fromJson(
          _json({
            'transactionId': null,
            'budgetId': _absent,
            'receiptId': null,
          }),
        );

        expect(notification.transactionId, isNull);
        expect(notification.budgetId, isNull);
        expect(notification.receiptId, isNull);
      });

      test(
        'numeric strings and whole doubles are read, anything else is null',
        () {
          final notification = AppNotification.fromJson(
            _json({
              'transactionId': '500',
              'budgetId': 100.0,
              'receiptId': 'x',
            }),
          );

          expect(notification.transactionId, 500);
          expect(notification.budgetId, 100);
          expect(notification.receiptId, isNull);
        },
      );

      test('a fractional or non-finite number is not an id', () {
        final notification = AppNotification.fromJson(
          _json({'budgetId': 1.5, 'receiptId': double.nan}),
        );

        expect(notification.budgetId, isNull);
        expect(notification.receiptId, isNull);
      });
    });

    test('a missing or malformed date is null', () {
      expect(
        AppNotification.fromJson(_json({'createdAt': null})).createdAt,
        isNull,
      );
      expect(
        AppNotification.fromJson(_json({'createdAt': 'hôm qua'})).createdAt,
        isNull,
      );
      expect(
        AppNotification.fromJson(_json({'createdAt': 20260921})).createdAt,
        isNull,
      );
    });

    test('a missing message is an empty text', () {
      expect(
        AppNotification.fromJson(_json({'message': null})).message,
        isEmpty,
      );
    });

    test('an id that is missing or not a whole number is refused', () {
      for (final id in <Object?>[_absent, null, 'abc', 7.5]) {
        expect(
          () => AppNotification.fromJson(_json({'id': id})),
          throwsFormatException,
          reason: '$id',
        );
      }
    });
  });

  group('AppNotification', () {
    test('copyWith changes the read flag only', () {
      final unread = AppNotification.fromJson(_json());

      final read = unread.copyWith(isRead: true);

      expect(read.isRead, isTrue);
      expect(read, unread.copyWith(isRead: true));
      expect(read, isNot(unread));
      expect(read.copyWith(isRead: false), unread);
      expect(unread.copyWith(), unread);
    });
  });

  group('NotificationPage.fromJson', () {
    Map<String, dynamic> page(List<Object?> content, {bool hasNext = true}) => {
      'content': content,
      'page': 1,
      'size': 20,
      'totalElements': 45,
      'totalPages': 3,
      'hasNext': hasNext,
    };

    test('reads the notifications in order and the paging figures', () {
      final result = NotificationPage.fromJson(
        page([
          _json({'id': 9, 'type': 'BUDGET_EXCEEDED'}),
          _json({'id': 8, 'isRead': true}),
        ]),
      );

      expect(result.content.map((n) => n.id), [9, 8]);
      expect(result.content.last.isRead, isTrue);
      expect(result.page, 1);
      expect(result.size, 20);
      expect(result.totalElements, 45);
      expect(result.totalPages, 3);
      expect(result.hasNext, isTrue);
    });

    test('the last page has no next one; a missing flag means none', () {
      expect(
        NotificationPage.fromJson(page([], hasNext: false)).hasNext,
        isFalse,
      );
      expect(
        NotificationPage.fromJson({'content': <Object?>[]}).hasNext,
        isFalse,
      );
    });

    test(
      'skips and logs a notification that cannot be read, and keeps the others',
      () async {
        await withCapturedLogs((logs) async {
          final result = NotificationPage.fromJson(
            page([
              _json({'id': 9}),
              _json({'id': null}),
              'not an object',
              _json({'id': 7, 'type': 'SOMETHING_NEW'}),
            ]),
          );

          expect(result.content.map((n) => n.id), [9, 7]);
          expect(result.content.last.type, NotificationType.unknown);
          expect(
            logs.where((log) => log.startsWith('Notification skipped')),
            hasLength(2),
          );
        });
      },
    );

    test('an answer without a content list is not a page', () {
      expect(
        () => NotificationPage.fromJson({'content': null}),
        throwsFormatException,
      );
      expect(
        () => NotificationPage.fromJson({'content': 'nothing'}),
        throwsFormatException,
      );
    });

    test('the content cannot be changed by whoever holds the page', () {
      final result = NotificationPage.fromJson(page([_json()]));

      expect(
        () => result.content.add(AppNotification.fromJson(_json())),
        throwsUnsupportedError,
      );
    });
  });
}
