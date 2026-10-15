import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/models/app_notification.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../helpers/fake_backend.dart';

const String _list = '/api/notifications';
const String _unreadCount = '/api/notifications/unread-count';
const String _readAll = '/api/notifications/read-all';

Map<String, Object?> _notification(int id, {bool isRead = false}) => {
  'id': id,
  'type': 'BUDGET_WARNING',
  'message': 'Thông báo $id',
  'isRead': isRead,
  'createdAt': '2026-09-21T10:15:30',
  'transactionId': null,
  'budgetId': 100,
  'receiptId': null,
};

/// Nothing that identifies the user may travel in a notification request: the server reads the owner from the token.
void _expectNoUserId(RequestOptions request) {
  expect(request.uri.queryParameters.keys, isNot(contains('userId')));
  expect(request.uri.toString(), isNot(contains('userId')));
  final body = request.data;
  if (body is Map) expect(body.keys, isNot(contains('userId')));
}

void main() {
  // The 401 interceptor reads the navigator key, and a GlobalKey needs the binding.
  TestWidgetsFlutterBinding.ensureInitialized();

  final backend = FakeBackend();

  setUpAll(() {
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    backend.reset();
    await SessionService.clearSession();
    // A signed-in user whose id the app knows: none of the calls may send it.
    await SessionService.setCurrentUserId(42);
    await SessionService.setTokens('access-token', 'refresh-token');
  });

  tearDown(() async {
    await SessionService.clearSession();
  });

  group('getNotifications', () {
    test(
      'asks for one page with only page and size, and reads it into typed notifications',
      () async {
        backend.on(
          'GET',
          _list,
          (_) => okResponse({
            'content': [_notification(9), _notification(8, isRead: true)],
            'page': 1,
            'size': 2,
            'totalElements': 5,
            'totalPages': 3,
            'hasNext': true,
          }),
        );

        final page = await RemoteApiHelper.instance.getNotifications(
          page: 1,
          size: 2,
        );

        final request = backend.requests.single;
        expect(request.method, 'GET');
        expect(request.path, _list);
        expect(request.queryParameters, {'page': 1, 'size': 2});
        expect(request.headers['Authorization'], 'Bearer access-token');
        _expectNoUserId(request);
        expect(page.content.map((n) => n.id), [9, 8]);
        expect(page.content.last.isRead, isTrue);
        expect(page.hasNext, isTrue);
        expect(page.totalElements, 5);
      },
    );

    test('a refusal of the server reaches the caller as a DioException', () {
      backend.on(
        'GET',
        _list,
        (_) => jsonResponse(400, {
          'code': 400,
          'message': 'Dữ liệu không hợp lệ: Số trang phải lớn hơn hoặc bằng 0',
        }),
      );

      expect(
        RemoteApiHelper.instance.getNotifications(page: 0, size: 20),
        throwsA(isA<DioException>()),
      );
    });

    test('an answer that is not a page is a FormatException', () {
      backend.on('GET', _list, (_) => okResponse(null));

      expect(
        RemoteApiHelper.instance.getNotifications(page: 0, size: 20),
        throwsFormatException,
      );
    });
  });

  group('getUnreadNotificationCount', () {
    test('reads the number of the envelope', () async {
      backend.on('GET', _unreadCount, (_) => okResponse(123));

      expect(await RemoteApiHelper.instance.getUnreadNotificationCount(), 123);

      final request = backend.requests.single;
      expect(request.method, 'GET');
      expect(request.path, _unreadCount);
      expect(request.queryParameters, isEmpty);
      _expectNoUserId(request);
    });

    test('an answer without a number is a FormatException', () {
      backend.on('GET', _unreadCount, (_) => okResponse('many'));

      expect(
        RemoteApiHelper.instance.getUnreadNotificationCount(),
        throwsFormatException,
      );
    });
  });

  group('markNotificationRead', () {
    test(
      'sends a bodiless PATCH to the id and returns the notification as the server has it now',
      () async {
        backend.on(
          'PATCH',
          '$_list/7/read',
          (_) => okResponse(_notification(7, isRead: true)),
        );

        final saved = await RemoteApiHelper.instance.markNotificationRead(7);

        final request = backend.requests.single;
        expect(request.method, 'PATCH');
        expect(request.path, '$_list/7/read');
        expect(request.data, isNull);
        expect(request.queryParameters, isEmpty);
        _expectNoUserId(request);
        expect(saved.id, 7);
        expect(saved.isRead, isTrue);
        expect(saved.type, NotificationType.budgetWarning);
      },
    );

    test(
      'a notification that is gone or not the user\'s is a 404 DioException',
      () async {
        backend.on(
          'PATCH',
          '$_list/7/read',
          (_) => jsonResponse(404, {
            'code': 404,
            'message': 'Không tìm thấy thông báo',
          }),
        );

        await expectLater(
          RemoteApiHelper.instance.markNotificationRead(7),
          throwsA(
            isA<DioException>().having(
              (e) => e.response?.statusCode,
              'status',
              404,
            ),
          ),
        );
      },
    );
  });

  group('markAllNotificationsRead', () {
    test(
      'sends a bodiless PATCH to read-all and returns how many changed',
      () async {
        backend.on('PATCH', _readAll, (_) => okResponse(4));

        expect(await RemoteApiHelper.instance.markAllNotificationsRead(), 4);

        final request = backend.requests.single;
        expect(request.method, 'PATCH');
        expect(request.path, _readAll);
        expect(request.data, isNull);
        _expectNoUserId(request);
      },
    );
  });
}
