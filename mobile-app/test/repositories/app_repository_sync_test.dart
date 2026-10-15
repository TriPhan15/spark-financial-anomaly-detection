import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/models/sync_status.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/repositories/app_repository.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';

import '../helpers/captured_logs.dart';
import '../helpers/fake_backend.dart';
import '../helpers/fake_local_store.dart';

const int _userId = 1;
const String _checkout = '/api/transactions/checkout';
const String _mine = '/api/transactions/mine';
const String _sendFailedLog = 'Lỗi gửi giao dịch';

TransactionModel _draft() => TransactionModel.draft(
  title: 'Cà phê',
  category: 'Ăn uống',
  amount: 45000,
  date: DateTime(2026, 9, 20, 8, 30),
  isExpense: true,
);

/// What core-backend answers to a first checkout.
ResponseBody _created() => okResponse({
  'transactionId': 5,
  'finalAmount': 45000,
  'remainingBalance': 955000,
  'warning': '',
});

/// The network is gone: the request never reaches the server.
Never _noNetwork(RequestOptions options) => throw DioException(
  requestOptions: options,
  type: DioExceptionType.connectionError,
  error: 'no network',
);

void main() {
  // The interceptor of RemoteApiHelper answers a 401 by asking navigatorKey for the navigator, to go back to the login
  // screen, and a GlobalKey lookup needs a binding. Without one it throws inside the interceptor; Dio ignores that error,
  // so the request never completes and the test ends with a timeout. With a binding the key finds no navigator (nothing
  // is mounted here) and the 401 comes out as a plain DioException.
  TestWidgetsFlutterBinding.ensureInitialized();

  final backend = FakeBackend();
  late FakeLocalStore store;

  setUpAll(() {
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
  });

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    AppConfig.mode = AppMode.full;
    store = FakeLocalStore();
    AppRepository.instance.localStore = store;
    backend.reset();
  });

  tearDown(() => AppConfig.mode = AppMode.full);

  /// The bodies of the requests that reached POST /api/transactions/checkout.
  List<Map<String, dynamic>> sentBodies() => [
    for (final request in backend.requests)
      if (request.path == _checkout)
        Map<String, dynamic>.from(request.data as Map),
  ];

  group('createTransaction', () {
    test(
      'sends the transaction with its clientId and leaves it flagged synced',
      () async {
        backend.on('POST', _checkout, (_) => _created());
        final draft = _draft();

        final status = await AppRepository.instance.createTransaction(
          _userId,
          draft,
        );

        expect(status, SyncStatus.synced);
        expect(sentBodies(), hasLength(1));
        expect(sentBodies().single['clientId'], draft.clientId);
        expect(sentBodies().single['categoryName'], 'Ăn uống');
        expect(sentBodies().single['amount'], 45000.0);
        expect(store.rows.single.syncStatus, SyncStatus.synced);
        expect(
          await AppRepository.instance.getUnsyncedTransactions(_userId),
          isEmpty,
        );
      },
    );

    test(
      'writes the transaction as pending before the request goes out, so a crash cannot lose it',
      () async {
        final draft = _draft();
        SyncStatus? duringRequest;
        backend.on('POST', _checkout, (_) {
          duringRequest = store.byClientId(draft.clientId!)?.syncStatus;
          return _created();
        });

        await AppRepository.instance.createTransaction(_userId, draft);

        expect(duringRequest, SyncStatus.pending);
      },
    );

    test(
      'without a network the transaction stays on the device flagged error, and nothing is thrown',
      () {
        return withCapturedLogs((logs) async {
          backend.on('POST', _checkout, _noNetwork);
          final draft = _draft();

          final status = await AppRepository.instance.createTransaction(
            _userId,
            draft,
          );

          expect(status, SyncStatus.error);
          expect(store.rows.single.clientId, draft.clientId);
          expect(store.rows.single.syncStatus, SyncStatus.error);
          expect(
            await AppRepository.instance.getUnsyncedTransactions(_userId),
            hasLength(1),
          );
          expect(
            logs.where((line) => line.contains(_sendFailedLog)),
            isNotEmpty,
          );
        });
      },
    );

    test(
      'a timeout, a server error and an expired session are kept for a resend, not treated as a refusal',
      () {
        return withCapturedLogs((logs) async {
          final failures = <FutureOr<ResponseBody> Function(RequestOptions)>[
            (options) => throw DioException(
              requestOptions: options,
              type: DioExceptionType.receiveTimeout,
            ),
            (_) => jsonResponse(500, {'code': 500, 'message': 'Lỗi hệ thống'}),
            (_) => jsonResponse(401, {'code': 401, 'message': 'Hết phiên'}),
            (_) => jsonResponse(408, {
              'code': 408,
              'message': 'Hết thời gian chờ',
            }),
            (_) => jsonResponse(429, {
              'code': 429,
              'message': 'Quá nhiều yêu cầu',
            }),
          ];
          for (final failure in failures) {
            store.clear();
            backend.on('POST', _checkout, failure);

            final status = await AppRepository.instance.createTransaction(
              _userId,
              _draft(),
            );

            expect(status, SyncStatus.error);
            expect(store.rows.single.syncStatus, SyncStatus.error);
          }
        });
      },
    );

    test(
      'a refusal of the server is thrown with its reason, and nothing is kept: the user still has the form',
      () {
        return withCapturedLogs((logs) async {
          backend.on(
            'POST',
            _checkout,
            (_) => jsonResponse(400, {
              'code': 400,
              'message': 'Số dư ví không đủ',
            }),
          );

          await expectLater(
            AppRepository.instance.createTransaction(_userId, _draft()),
            throwsA(
              isA<TransactionRefusedException>().having(
                (e) => e.message,
                'message',
                'Số dư ví không đủ',
              ),
            ),
          );

          expect(store.rows, isEmpty);
        });
      },
    );

    test('a refusal without a reason still says something', () {
      return withCapturedLogs((logs) async {
        backend.on('POST', _checkout, (_) => jsonResponse(422, {}));

        await expectLater(
          AppRepository.instance.createTransaction(_userId, _draft()),
          throwsA(
            isA<TransactionRefusedException>().having(
              (e) => e.message,
              'message',
              isNotEmpty,
            ),
          ),
        );
      });
    });

    test(
      'without a server the transaction is only stored, as synced, and no request is made',
      () async {
        AppConfig.mode = AppMode.demo;

        final status = await AppRepository.instance.createTransaction(
          _userId,
          _draft(),
        );

        expect(status, SyncStatus.synced);
        expect(backend.requests, isEmpty);
        expect(store.rows.single.syncStatus, SyncStatus.synced);
        expect(
          await AppRepository.instance.getUnsyncedTransactions(_userId),
          isEmpty,
        );
      },
    );
  });

  group('resendTransaction', () {
    test(
      'sends the same clientId again and flags the transaction synced',
      () async {
        final draft = _draft();
        await store.insertTransaction(
          draft.withSyncStatus(SyncStatus.error),
          _userId,
        );
        SyncStatus? duringRequest;
        backend.on('POST', _checkout, (_) {
          duringRequest = store.byClientId(draft.clientId!)?.syncStatus;
          return _created();
        });

        final status = await AppRepository.instance.resendTransaction(
          _userId,
          draft,
        );

        expect(status, SyncStatus.synced);
        expect(
          duringRequest,
          SyncStatus.pending,
          reason: 'it is being sent again',
        );
        expect(sentBodies().single['clientId'], draft.clientId);
        expect(store.rows.single.syncStatus, SyncStatus.synced);
      },
    );

    test(
      'when the first send arrived and only the answer was lost, the resend carries the same clientId',
      () {
        return withCapturedLogs((logs) async {
          // An idempotent server: it stores a transaction once per clientId, whatever the number of sends.
          final onServer = <String>{};
          var answerLost = true;
          backend.on('POST', _checkout, (options) {
            onServer.add((options.data as Map)['clientId'] as String);
            if (answerLost) {
              throw DioException(
                requestOptions: options,
                type: DioExceptionType.receiveTimeout,
              );
            }
            return _created();
          });
          final draft = _draft();

          expect(
            await AppRepository.instance.createTransaction(_userId, draft),
            SyncStatus.error,
          );
          answerLost = false;
          expect(
            await AppRepository.instance.resendTransaction(_userId, draft),
            SyncStatus.synced,
          );

          expect(sentBodies().map((body) => body['clientId']), [
            draft.clientId,
            draft.clientId,
          ]);
          expect(onServer, {
            draft.clientId,
          }, reason: 'one transaction on the server');
          expect(store.rows.single.syncStatus, SyncStatus.synced);
        });
      },
    );

    test('a resend that fails again keeps the transaction flagged error', () {
      return withCapturedLogs((logs) async {
        final draft = _draft();
        await store.insertTransaction(
          draft.withSyncStatus(SyncStatus.error),
          _userId,
        );
        backend.on('POST', _checkout, _noNetwork);

        final status = await AppRepository.instance.resendTransaction(
          _userId,
          draft,
        );

        expect(status, SyncStatus.error);
        expect(store.rows.single.syncStatus, SyncStatus.error);
      });
    });

    test(
      'a refusal on a resend is thrown, and the transaction stays (flagged error) so the user can delete it',
      () {
        return withCapturedLogs((logs) async {
          final draft = _draft();
          await store.insertTransaction(
            draft.withSyncStatus(SyncStatus.error),
            _userId,
          );
          backend.on(
            'POST',
            _checkout,
            (_) => jsonResponse(404, {
              'code': 404,
              'message': 'Danh mục không tồn tại',
            }),
          );

          await expectLater(
            AppRepository.instance.resendTransaction(_userId, draft),
            throwsA(
              isA<TransactionRefusedException>().having(
                (e) => e.message,
                'message',
                'Danh mục không tồn tại',
              ),
            ),
          );

          expect(store.rows.single.syncStatus, SyncStatus.error);
        });
      },
    );

    test(
      'a transaction that was not created on this device has no clientId and cannot be resent',
      () async {
        final fromServer = TransactionModel.fromJson({
          'id': 9,
          'amount': 1,
          'category': 'Khác',
        });

        await expectLater(
          AppRepository.instance.resendTransaction(_userId, fromServer),
          throwsArgumentError,
        );
        expect(backend.requests, isEmpty);
      },
    );
  });

  group('reading', () {
    Map<String, Object?> serverRow() => {
      'id': 1,
      'title': 'Trên máy chủ',
      'amount': 1000,
      'date': '2026-09-20T08:00:00',
      'category': 'Ăn uống',
      'isExpense': true,
    };

    test(
      'the list is the transactions waiting to be sent, then those of the server, and only the ones of the user',
      () async {
        backend.on('GET', _mine, (_) => okResponse([serverRow()]));
        final waiting = _draft().withSyncStatus(SyncStatus.pending);
        final failed = _draft().withSyncStatus(SyncStatus.error);
        await store.insertTransaction(waiting, _userId);
        await store.insertTransaction(failed, _userId);
        await store.insertTransaction(
          _draft().withSyncStatus(SyncStatus.synced),
          _userId,
        );
        await store.insertTransaction(
          _draft().withSyncStatus(SyncStatus.error),
          2,
        );

        final list = await AppRepository.instance.getTransactions(_userId);

        expect(list.map((t) => t.clientId), [
          waiting.clientId,
          failed.clientId,
          null,
        ]);
        expect(list.map((t) => t.syncStatus), [
          SyncStatus.pending,
          SyncStatus.error,
          SyncStatus.synced,
        ]);
        expect(list.map((t) => t.id), [null, null, '1']);
      },
    );

    test(
      'when the server cannot be reached the list throws, and the waiting transactions can still be read',
      () async {
        backend.on('GET', _mine, _noNetwork);
        await store.insertTransaction(
          _draft().withSyncStatus(SyncStatus.error),
          _userId,
        );

        await expectLater(
          AppRepository.instance.getTransactions(_userId),
          throwsA(isA<DioException>()),
        );

        expect(
          await AppRepository.instance.getUnsyncedTransactions(_userId),
          hasLength(1),
        );
      },
    );

    test(
      'deleting a transaction that was never confirmed only touches the device, never the server',
      () async {
        final tx = _draft().withSyncStatus(SyncStatus.error);
        await store.insertTransaction(tx, _userId);

        await AppRepository.instance.deleteUnsyncedTransaction(
          _userId,
          tx.clientId!,
        );

        expect(store.rows, isEmpty);
        expect(backend.requests, isEmpty);
      },
    );
  });
}
