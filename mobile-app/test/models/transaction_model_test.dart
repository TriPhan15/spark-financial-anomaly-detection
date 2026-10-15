import 'package:flutter_test/flutter_test.dart';

import 'package:finance_ai_app/models/receipt_status.dart';
import 'package:finance_ai_app/models/sync_status.dart';
import 'package:finance_ai_app/models/transaction_model.dart';

final RegExp _uuidV4 = RegExp(
  r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
);

TransactionModel _draft({String title = 'Cà phê'}) => TransactionModel.draft(
  title: title,
  category: 'Ăn uống',
  amount: 45000,
  date: DateTime(2026, 9, 20, 8, 30),
  isExpense: true,
);

void main() {
  group('ReceiptStatus.fromServer', () {
    test(
      'SUCCESS and FAILED map to their own state, whatever the letter case or the spaces',
      () {
        expect(ReceiptStatus.fromServer('SUCCESS'), ReceiptStatus.success);
        expect(ReceiptStatus.fromServer('success'), ReceiptStatus.success);
        expect(ReceiptStatus.fromServer(' Failed '), ReceiptStatus.failed);
        expect(ReceiptStatus.fromServer('FAILED'), ReceiptStatus.failed);
      },
    );

    test(
      'a receipt that is scanned, being read or waiting for an administrator is processing',
      () {
        for (final name in [
          'PENDING',
          'WAITING_FOR_REVIEW',
          'PROCESSING',
          'pending',
        ]) {
          expect(
            ReceiptStatus.fromServer(name),
            ReceiptStatus.processing,
            reason: name,
          );
        }
      },
    );

    test(
      'nothing is shown for no receipt, a withdrawn one or a status the app does not know',
      () {
        for (final Object? raw in [
          null,
          '',
          '   ',
          'CANCELED',
          'SOMETHING_NEW',
          42,
          true,
        ]) {
          expect(ReceiptStatus.fromServer(raw), isNull, reason: '$raw');
        }
      },
    );

    test('the badge texts are the ones of the task', () {
      expect(ReceiptStatus.processing.label, 'Đang xử lý');
      expect(ReceiptStatus.success.label, 'Hoàn tất');
      expect(ReceiptStatus.failed.label, 'Lỗi · cần xác nhận lại');
    });
  });

  group('TransactionModel.fromJson', () {
    test(
      'reads a transaction of the server together with the state of its receipt',
      () {
        final tx = TransactionModel.fromJson({
          'id': 12,
          'title': 'Cà phê',
          'amount': 45000,
          'date': '2026-09-20T08:30:00',
          'category': 'Ăn uống',
          'isExpense': true,
          'imageUrl': 'https://img.example/receipt.jpg',
          'receiptStatus': 'FAILED',
        });

        expect(tx.id, '12');
        expect(tx.title, 'Cà phê');
        expect(tx.amount, 45000.0);
        expect(tx.date, DateTime(2026, 9, 20, 8, 30));
        expect(tx.category, 'Ăn uống');
        expect(tx.iconName, 'Ăn uống');
        expect(tx.isExpense, isTrue);
        expect(tx.imageUrl, 'https://img.example/receipt.jpg');
        expect(tx.receiptStatus, ReceiptStatus.failed);
      },
    );

    test(
      'a transaction without a receipt has no receipt state, which is what the server sends today',
      () {
        final tx = TransactionModel.fromJson({
          'id': 1,
          'title': 'Lương',
          'amount': 9000000,
          'date': '2026-09-01T09:00:00',
          'category': 'Thu nhập',
          'isExpense': false,
        });

        expect(tx.receiptStatus, isNull);
        expect(tx.imageUrl, isNull);
        expect(tx.isExpense, isFalse);
      },
    );

    test(
      'an unknown receipt status is ignored instead of breaking the list',
      () {
        final tx = TransactionModel.fromJson({
          'id': 1,
          'amount': 1,
          'category': 'Khác',
          'receiptStatus': 'NEW_STATE',
        });

        expect(tx.receiptStatus, isNull);
      },
    );

    test(
      'falls back the way the list always did: the category as title, "Khác" as category, now as date',
      () {
        final before = DateTime.now();
        final tx = TransactionModel.fromJson({'id': 3});

        expect(tx.title, '');
        expect(tx.category, 'Khác');
        expect(tx.amount, 0.0);
        expect(tx.isExpense, isFalse);
        expect(tx.date.isBefore(before), isFalse);

        final noTitle = TransactionModel.fromJson({
          'id': 4,
          'category': 'Đi lại',
        });
        expect(noTitle.title, 'Đi lại');
      },
    );
  });

  group('offline (SQLite) mapping', () {
    test(
      'never carries the receipt state, so local inserts and reads keep their columns',
      () {
        final tx = TransactionModel(
          title: 'Cà phê',
          amount: 45000,
          date: DateTime(2026, 9, 20),
          category: 'Ăn uống',
          isExpense: true,
          receiptStatus: ReceiptStatus.success,
        );

        expect(
          tx.toDbMap(1).keys,
          unorderedEquals([
            'user_id',
            'title',
            'amount',
            'date',
            'category',
            'is_expense',
            'icon_name',
            'wallet_id',
            'client_id', 'sync_status', // the sync state (Task A8)
          ]),
        );
        expect(
          tx.toMap().keys,
          unorderedEquals([
            'title',
            'amount',
            'date',
            'category',
            'isExpense',
            'icon_name',
          ]),
        );
      },
    );

    test('rows read from SQLite have no receipt state', () {
      final tx = TransactionModel.fromDbMap({
        'id': 3,
        'title': 'Cà phê',
        'amount': 45000,
        'date': '2026-09-20T08:30:00.000',
        'category': 'Ăn uống',
        'is_expense': 1,
        'icon_name': 'Ăn uống',
        'wallet_id': null,
      });

      expect(tx.receiptStatus, isNull);
      expect(tx.isExpense, isTrue);
    });
  });

  group('client id', () {
    test(
      'a new transaction gets a UUID v4, and every new transaction gets its own',
      () {
        final ids = {for (var i = 0; i < 50; i++) _draft().clientId};

        expect(ids, hasLength(50));
        for (final id in ids) {
          expect(id, matches(_uuidV4));
        }
      },
    );

    test(
      'a transaction read from the server has none, because the server does not send it back',
      () {
        final tx = TransactionModel.fromJson({
          'id': 1,
          'amount': 1,
          'category': 'Khác',
        });

        expect(tx.clientId, isNull);
      },
    );

    test(
      'the body of the checkout carries what the add form always sent, plus the client id',
      () {
        final tx = _draft();

        expect(tx.toCheckoutJson(), {
          'amount': 45000.0,
          'note': 'Cà phê',
          'location': 'Cà phê',
          'paymentMethod': 'WALLET',
          'transactionDate': DateTime(2026, 9, 20, 8, 30).toIso8601String(),
          'categoryName': 'Ăn uống',
          'isExpense': true,
          'clientId': tx.clientId,
        });
      },
    );

    test(
      'a transaction without a title is sent with the default location, and one without an id sends no clientId',
      () {
        final draft = _draft(title: '');
        expect(draft.toCheckoutJson()['location'], 'Trong ứng dụng');

        final withoutId = TransactionModel(
          title: 'Lương',
          amount: 1,
          date: DateTime(2026, 9, 1),
          category: 'Thu nhập',
          isExpense: false,
        );
        expect(withoutId.toCheckoutJson().containsKey('clientId'), isFalse);
      },
    );
  });

  group('sync state', () {
    test('everything that is not created on the device counts as synced', () {
      expect(
        TransactionModel.fromJson({'id': 1, 'amount': 1}).syncStatus,
        SyncStatus.synced,
      );
      expect(
        _draft().syncStatus,
        SyncStatus.synced,
        reason: 'the repository marks it pending when it stores it',
      );
      expect(_draft().isUnsynced, isFalse);
    });

    test(
      'pending and error are the two states in which the server has not confirmed the transaction',
      () {
        expect(_draft().withSyncStatus(SyncStatus.pending).isUnsynced, isTrue);
        expect(_draft().withSyncStatus(SyncStatus.error).isUnsynced, isTrue);
        expect(_draft().withSyncStatus(SyncStatus.synced).isUnsynced, isFalse);
      },
    );

    test(
      'withSyncStatus changes the state and nothing else, so a resend sends the same transaction',
      () {
        final original = _draft();
        final moved = original.withSyncStatus(SyncStatus.error);

        expect(moved.syncStatus, SyncStatus.error);
        expect(moved.clientId, original.clientId);
        expect(moved.toCheckoutJson(), original.toCheckoutJson());
      },
    );

    test('the client id and the state survive a trip through SQLite', () {
      final draft = _draft().withSyncStatus(SyncStatus.error);

      final row = {...draft.toDbMap(7), 'id': 42};
      final read = TransactionModel.fromDbMap(row);

      expect(row['client_id'], draft.clientId);
      expect(row['sync_status'], 'error');
      expect(read.clientId, draft.clientId);
      expect(read.syncStatus, SyncStatus.error);
      expect(
        read.toCheckoutJson(),
        draft.toCheckoutJson(),
        reason: 'a resend sends exactly what the first send did',
      );
    });

    test(
      'a row that is not synced has no server id, so its row number cannot pass for one',
      () {
        for (final status in [SyncStatus.pending, SyncStatus.error]) {
          final read = TransactionModel.fromDbMap({
            ..._draft().withSyncStatus(status).toDbMap(7),
            'id': 42,
          });
          expect(read.id, isNull, reason: '$status');
        }
        final synced = TransactionModel.fromDbMap({
          ..._draft().toDbMap(7),
          'id': 42,
        });
        expect(
          synced.id,
          '42',
          reason: 'local mode keeps addressing its rows by their id',
        );
      },
    );

    test(
      'a row from before the columns existed reads as synced, and so does an unknown value',
      () {
        final old = {..._draft().toDbMap(7), 'id': 3}
          ..remove('client_id')
          ..remove('sync_status');
        expect(TransactionModel.fromDbMap(old).syncStatus, SyncStatus.synced);
        expect(TransactionModel.fromDbMap(old).clientId, isNull);
        expect(TransactionModel.fromDbMap(old).id, '3');

        expect(SyncStatus.fromDb('something-new'), SyncStatus.synced);
        expect(SyncStatus.fromDb(null), SyncStatus.synced);
      },
    );

    test(
      'the stored values are stable, and the badge texts are the ones of the task',
      () {
        expect(
          {for (final s in SyncStatus.values) s: s.dbValue},
          {
            SyncStatus.pending: 'pending',
            SyncStatus.synced: 'synced',
            SyncStatus.error: 'error',
          },
        );
        for (final status in SyncStatus.values) {
          expect(SyncStatus.fromDb(status.dbValue), status);
        }
        expect(SyncStatus.pending.label, 'Chờ gửi');
        expect(SyncStatus.error.label, 'Gửi lỗi');
        expect(SyncStatus.synced.label, 'Đã đồng bộ');
      },
    );
  });
}
