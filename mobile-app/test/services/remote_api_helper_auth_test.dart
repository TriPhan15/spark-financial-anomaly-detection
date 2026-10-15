import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/services/session_service.dart';

import '../helpers/captured_logs.dart';
import '../helpers/fake_backend.dart';

const String _refreshCall = 'POST /api/auth/refresh';
const String _budgetsCall = 'GET /api/budgets';

ResponseBody _unauthorized() => jsonResponse(401, {
  'code': 401,
  'message': 'Phiên đăng nhập đã hết hiệu lực. Vui lòng đăng nhập lại.',
});

/// What the backend answers for an access token past its expiry: a 401, like every other token it refuses.
ResponseBody _expired() => jsonResponse(401, {
  'code': 401,
  'message': 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
});

ResponseBody _tokenPair(String token, String refreshToken) =>
    okResponse({'token': token, 'refreshToken': refreshToken});

void main() {
  // The 401 interceptor reads the navigator key when a refresh fails, and a GlobalKey needs the binding.
  TestWidgetsFlutterBinding.ensureInitialized();

  final backend = FakeBackend();

  setUpAll(() {
    RemoteApiHelper.instance.init(httpClientAdapter: backend);
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    backend.reset();
    await SessionService.clearSession();
    await SessionService.setTokens('access-token', 'refresh-token');
  });

  tearDown(() async {
    await SessionService.clearSession();
  });

  int refreshCalls() => backend.calls.where((c) => c == _refreshCall).length;

  List<Object?> bearersOf(String call) => backend.requests
      .where((r) => '${r.method} ${r.path}' == call)
      .map((r) => r.headers['Authorization'])
      .toList();

  /// /api/budgets refuses the first access token with a 401 and answers any other with an empty list.
  void budgetsRefuseTheFirstToken() {
    backend.on(
      'GET',
      '/api/budgets',
      (options) => options.headers['Authorization'] == 'Bearer access-token'
          ? _unauthorized()
          : okResponse([]),
    );
  }

  Matcher isDioError(int status) => isA<DioException>().having(
    (e) => e.response?.statusCode,
    'status code',
    status,
  );

  group('logout', () {
    test(
      'asks the server to revoke both tokens: the access token as bearer, the refresh token in the body',
      () async {
        backend.on(
          'POST',
          '/api/auth/logout',
          (_) => okResponse(null, message: 'Đăng xuất thành công!'),
        );

        await RemoteApiHelper.instance.logout();

        expect(backend.calls, ['POST /api/auth/logout']);
        expect(
          backend.requests.single.headers['Authorization'],
          'Bearer access-token',
        );
        expect(backend.requests.single.data, {'refreshToken': 'refresh-token'});
      },
    );

    test(
      'leaves the refresh token out of the body when none is stored',
      () async {
        SharedPreferences.setMockInitialValues({'auth_token': 'access-token'});
        await SessionService.init();
        backend.on('POST', '/api/auth/logout', (_) => okResponse(null));

        await RemoteApiHelper.instance.logout();

        expect(SessionService.currentRefreshToken, isNull);
        expect(backend.requests.single.data, isNot(contains('refreshToken')));
      },
    );

    test('calls nothing when there is no stored access token', () async {
      await SessionService.clearSession();

      await RemoteApiHelper.instance.logout();

      expect(backend.calls, isEmpty);
    });

    test(
      'an error of the server (500) is not thrown, so the caller can still sign out locally',
      () async {
        backend.on(
          'POST',
          '/api/auth/logout',
          (_) => jsonResponse(500, {'code': 500, 'message': 'Lỗi hệ thống'}),
        );

        await expectLater(RemoteApiHelper.instance.logout(), completes);

        expect(backend.calls, ['POST /api/auth/logout']);
      },
    );

    test('a network failure is not thrown either', () async {
      backend.on(
        'POST',
        '/api/auth/logout',
        (options) => throw DioException.connectionError(
          requestOptions: options,
          reason: 'no network',
        ),
      );

      await expectLater(RemoteApiHelper.instance.logout(), completes);

      expect(backend.calls, ['POST /api/auth/logout']);
    });

    test(
      'a 401 of the logout is not renewed: the session is being ended anyway',
      () async {
        backend.on('POST', '/api/auth/logout', (_) => _unauthorized());

        await withCapturedLogs((_) async {
          await expectLater(RemoteApiHelper.instance.logout(), completes);
        });

        expect(backend.calls, ['POST /api/auth/logout']);
      },
    );
  });

  group('changePassword', () {
    test(
      'sends the old and the new password and nothing else, as the logged-in caller',
      () async {
        backend.on(
          'PUT',
          '/api/auth/change-password',
          (_) => _tokenPair('changed-access', 'changed-refresh'),
        );

        await RemoteApiHelper.instance.changePassword(
          currentPassword: 'OldPass123',
          newPassword: 'NewPass456',
        );

        expect(backend.calls, ['PUT /api/auth/change-password']);
        expect(
          backend.requests.single.headers['Authorization'],
          'Bearer access-token',
        );
        expect(backend.requests.single.data, {
          'oldPassword': 'OldPass123',
          'newPassword': 'NewPass456',
        });
      },
    );

    test(
      'stores the token pair the server hands back at once, so the next request already carries the new token',
      () async {
        backend.on(
          'PUT',
          '/api/auth/change-password',
          (_) => _tokenPair('changed-access', 'changed-refresh'),
        );
        backend.on('GET', '/api/budgets', (_) => okResponse([]));

        await RemoteApiHelper.instance.changePassword(
          currentPassword: 'OldPass123',
          newPassword: 'NewPass456',
        );
        await RemoteApiHelper.instance.getBudgets();

        expect(SessionService.currentToken, 'changed-access');
        expect(SessionService.currentRefreshToken, 'changed-refresh');
        expect(bearersOf(_budgetsCall), ['Bearer changed-access']);
        expect(refreshCalls(), 0);
      },
    );

    test(
      'a refusal (400, wrong old password) reaches the caller and does not touch the session',
      () async {
        backend.on(
          'PUT',
          '/api/auth/change-password',
          (_) => jsonResponse(400, {
            'code': 400,
            'message': 'Mật khẩu cũ không chính xác',
          }),
        );

        await expectLater(
          RemoteApiHelper.instance.changePassword(
            currentPassword: 'wrong',
            newPassword: 'NewPass456',
          ),
          throwsA(isDioError(400)),
        );

        expect(SessionService.currentToken, 'access-token');
        expect(SessionService.currentRefreshToken, 'refresh-token');
      },
    );
  });

  group('an access token that the server refuses with 401', () {
    test(
      'is renewed silently: the app refreshes with the stored token, keeps the NEW pair and repeats the request',
      () async {
        budgetsRefuseTheFirstToken();
        backend.on(
          'POST',
          '/api/auth/refresh',
          (_) => _tokenPair('new-access-token', 'new-refresh-token'),
        );

        final budgets = await RemoteApiHelper.instance.getBudgets();

        expect(budgets, isEmpty);
        expect(backend.calls, [_budgetsCall, _refreshCall, _budgetsCall]);

        final refresh = backend.requests[1];
        expect(refresh.data, {'refreshToken': 'refresh-token'});
        // The paths already start with /api: a base URL that ends with /api as well would call /api/api/auth/refresh.
        expect(refresh.uri.path, endsWith('/api/auth/refresh'));
        expect(refresh.uri.path, isNot(contains('/api/api/')));

        expect(
          backend.requests[2].headers['Authorization'],
          'Bearer new-access-token',
        );
        expect(SessionService.currentToken, 'new-access-token');
        // The server uses the old refresh token up: keeping it would make the next renewal fail.
        expect(SessionService.currentRefreshToken, 'new-refresh-token');
      },
    );

    test(
      'an expired access token is renewed with one refresh, and the next expiry renews again with the NEW refresh token',
      () async {
        // Each access token works until the server calls it expired; the test decides when that happens.
        final expiredBearers = {'Bearer access-token'};
        backend.on(
          'GET',
          '/api/budgets',
          (options) => expiredBearers.contains(options.headers['Authorization'])
              ? _expired()
              : okResponse([]),
        );
        final pairs = [
          ('second-access', 'second-refresh'),
          ('third-access', 'third-refresh'),
        ];
        var refreshes = 0;
        backend.on('POST', '/api/auth/refresh', (_) {
          final (token, refreshToken) = pairs[refreshes++];
          return _tokenPair(token, refreshToken);
        });

        expect(await RemoteApiHelper.instance.getBudgets(), isEmpty);
        expect(refreshCalls(), 1);
        expect(SessionService.currentToken, 'second-access');

        // Later the renewed access token expires too. The shared renewal was cleared after it succeeded, so this expiry
        // starts a refresh of its own.
        expiredBearers.add('Bearer second-access');
        expect(await RemoteApiHelper.instance.getBudgets(), isEmpty);

        expect(refreshCalls(), 2);
        final refreshBodies = backend.requests
            .where((r) => '${r.method} ${r.path}' == _refreshCall)
            .map((r) => r.data)
            .toList();
        // The server uses a refresh token up: the first one is never presented again.
        expect(refreshBodies, [
          {'refreshToken': 'refresh-token'},
          {'refreshToken': 'second-refresh'},
        ]);
        expect(SessionService.currentToken, 'third-access');
        expect(SessionService.currentRefreshToken, 'third-refresh');
      },
    );

    test(
      'several requests refused together share ONE refresh, and each is repeated once with the new token',
      () async {
        budgetsRefuseTheFirstToken();
        backend.on(
          'POST',
          '/api/auth/refresh',
          (_) => _tokenPair('new-access-token', 'new-refresh-token'),
        );

        final results = await Future.wait([
          RemoteApiHelper.instance.getBudgets(),
          RemoteApiHelper.instance.getBudgets(),
          RemoteApiHelper.instance.getBudgets(),
        ]);

        expect(results, everyElement(isEmpty));
        // A second refresh would present the refresh token the first one used up, and be refused.
        expect(refreshCalls(), 1);
        expect(
          bearersOf(_budgetsCall),
          unorderedEquals([
            'Bearer access-token',
            'Bearer access-token',
            'Bearer access-token',
            'Bearer new-access-token',
            'Bearer new-access-token',
            'Bearer new-access-token',
          ]),
        );
      },
    );

    test(
      'a request that left before a renewal and got its 401 after it is repeated with the new token, without another refresh',
      () async {
        final heldAnswer = Completer<ResponseBody>();
        backend.on(
          'GET',
          '/api/notifications/unread-count',
          (options) => options.headers['Authorization'] == 'Bearer access-token'
              ? heldAnswer.future
              : okResponse(3),
        );
        budgetsRefuseTheFirstToken();
        backend.on(
          'POST',
          '/api/auth/refresh',
          (_) => _tokenPair('new-access-token', 'new-refresh-token'),
        );

        final unread = RemoteApiHelper.instance.getUnreadNotificationCount();
        await RemoteApiHelper.instance.getBudgets();
        heldAnswer.complete(_unauthorized());

        expect(await unread, 3);
        expect(refreshCalls(), 1);
      },
    );

    test(
      'a repeat that fails on its own passes its error on, and the renewed session stays',
      () async {
        backend.on(
          'GET',
          '/api/budgets',
          (options) => options.headers['Authorization'] == 'Bearer access-token'
              ? _unauthorized()
              : jsonResponse(500, {'code': 500, 'message': 'Lỗi hệ thống'}),
        );
        backend.on(
          'POST',
          '/api/auth/refresh',
          (_) => _tokenPair('new-access-token', 'new-refresh-token'),
        );

        await expectLater(
          RemoteApiHelper.instance.getBudgets(),
          throwsA(isDioError(500)),
        );

        expect(SessionService.currentToken, 'new-access-token');
        expect(SessionService.currentRefreshToken, 'new-refresh-token');
      },
    );

    test(
      'a request is repeated at most once: a 401 of the repeat is passed on without another refresh',
      () async {
        backend.on('GET', '/api/budgets', (_) => _unauthorized());
        backend.on(
          'POST',
          '/api/auth/refresh',
          (_) => _tokenPair('new-access-token', 'new-refresh-token'),
        );

        await expectLater(
          RemoteApiHelper.instance.getBudgets(),
          throwsA(isDioError(401)),
        );

        expect(backend.calls, [_budgetsCall, _refreshCall, _budgetsCall]);
      },
    );

    test(
      'a refused refresh ends the session: tokens and user cleared, every waiting request fails with its own 401',
      () async {
        await SessionService.setCurrentUserId(1);
        backend.on('GET', '/api/budgets', (_) => _unauthorized());
        backend.on('POST', '/api/auth/refresh', (_) => _unauthorized());

        final outcomes = await Future.wait(
          List.generate(
            3,
            (_) => RemoteApiHelper.instance.getBudgets().then<Object?>(
              (_) => null,
              onError: (Object error) => error,
            ),
          ),
        );

        expect(outcomes, everyElement(isDioError(401)));
        expect(refreshCalls(), 1);
        expect(SessionService.currentToken, isNull);
        expect(SessionService.currentRefreshToken, isNull);
        expect(SessionService.currentUserId, isNull);
      },
    );

    test(
      'after a refused refresh the shared renewal is cleared: the next session renews on its own again',
      () async {
        backend.on('GET', '/api/budgets', (_) => _unauthorized());
        backend.on('POST', '/api/auth/refresh', (_) => _unauthorized());
        await expectLater(
          RemoteApiHelper.instance.getBudgets(),
          throwsA(isDioError(401)),
        );
        expect(SessionService.currentToken, isNull);

        // Signed in again; this session's first token gets a 401 too, and this time the refresh works.
        await SessionService.setTokens('second-access', 'second-refresh');
        backend.reset();
        backend.on(
          'GET',
          '/api/budgets',
          (options) =>
              options.headers['Authorization'] == 'Bearer second-access'
              ? _unauthorized()
              : okResponse([]),
        );
        backend.on(
          'POST',
          '/api/auth/refresh',
          (_) => _tokenPair('third-access', 'third-refresh'),
        );

        expect(await RemoteApiHelper.instance.getBudgets(), isEmpty);
        expect(backend.calls, [_budgetsCall, _refreshCall, _budgetsCall]);
        expect(backend.requests[1].data, {'refreshToken': 'second-refresh'});
      },
    );

    test(
      'when the server cannot be reached for the refresh, the session is kept and the request fails with its 401',
      () async {
        budgetsRefuseTheFirstToken();
        backend.on(
          'POST',
          '/api/auth/refresh',
          (options) => throw DioException.connectionError(
            requestOptions: options,
            reason: 'no network',
          ),
        );

        await withCapturedLogs((logs) async {
          await expectLater(
            RemoteApiHelper.instance.getBudgets(),
            throwsA(isDioError(401)),
          );
          expect(logs.single, contains('it is kept'));
        });

        expect(SessionService.currentToken, 'access-token');
        expect(SessionService.currentRefreshToken, 'refresh-token');

        // The failed renewal is not handed to the next 401: that one tries again, and gets through.
        backend.on(
          'POST',
          '/api/auth/refresh',
          (_) => _tokenPair('new-access-token', 'new-refresh-token'),
        );
        expect(await RemoteApiHelper.instance.getBudgets(), isEmpty);
        expect(refreshCalls(), 2);
      },
    );

    test(
      'a password change on this device that lands while a refresh is refused keeps the device signed in with its pair',
      () async {
        // Completed by the fake backend the moment the refresh reaches it; its answer is held back until the test lets it go.
        final refreshReached = Completer<void>();
        final refreshAnswer = Completer<ResponseBody>();
        budgetsRefuseTheFirstToken();
        backend.on('POST', '/api/auth/refresh', (_) {
          if (!refreshReached.isCompleted) refreshReached.complete();
          return refreshAnswer.future;
        });
        backend.on(
          'PUT',
          '/api/auth/change-password',
          (_) => _tokenPair('changed-access', 'changed-refresh'),
        );

        final budgets = RemoteApiHelper.instance.getBudgets();
        // The 401 has come back and the refresh is out; the change lands before the refusal of the refresh. The timeout is
        // only a guard against a hang, not what the test waits for.
        await refreshReached.future.timeout(
          const Duration(seconds: 5),
          onTimeout: () => throw TestFailure(
            'The refresh never reached the backend within the test timeout.',
          ),
        );
        expect(refreshCalls(), 1);
        await RemoteApiHelper.instance.changePassword(
          currentPassword: 'OldPass123',
          newPassword: 'NewPass456',
        );
        refreshAnswer.complete(_unauthorized());

        expect(await budgets, isEmpty);
        // The refused refresh neither ended the session nor started a second refresh: the request was repeated once,
        // with the pair of the change.
        expect(backend.calls, [
          _budgetsCall,
          _refreshCall,
          'PUT /api/auth/change-password',
          _budgetsCall,
        ]);
        expect(bearersOf(_budgetsCall).last, 'Bearer changed-access');
        expect(SessionService.currentToken, 'changed-access');
        expect(SessionService.currentRefreshToken, 'changed-refresh');
      },
    );
  });

  group('the sign-in and recovery endpoints', () {
    test(
      'a 401 of the login is its answer: no refresh, the session is left alone',
      () async {
        backend.on(
          'POST',
          '/api/auth/login',
          (_) => jsonResponse(401, {'message': 'Sai email hoặc mật khẩu!'}),
        );

        await expectLater(
          RemoteApiHelper.instance.login('a@b.c', 'wrong'),
          throwsA('Sai email hoặc mật khẩu!'),
        );

        expect(backend.calls, ['POST /api/auth/login']);
        expect(SessionService.currentToken, 'access-token');
      },
    );

    test('a 401 of the password reset is not renewed either', () async {
      backend.on('POST', '/api/auth/reset-password', (_) => _unauthorized());

      await expectLater(
        RemoteApiHelper.instance.resetPassword(
          email: 'a@b.c',
          otp: '123456',
          newPassword: 'NewPass456',
        ),
        throwsA(isDioError(401)),
      );

      expect(backend.calls, ['POST /api/auth/reset-password']);
    });

    test(
      'a 401 of the OTP request is its answer too: no refresh, the session is left alone',
      () async {
        backend.on('POST', '/api/auth/forgot-password', (_) => _unauthorized());

        await expectLater(
          RemoteApiHelper.instance.forgotPassword(email: 'a@b.c'),
          throwsA(isDioError(401)),
        );

        expect(backend.calls, ['POST /api/auth/forgot-password']);
        expect(SessionService.currentToken, 'access-token');
        expect(SessionService.currentRefreshToken, 'refresh-token');
      },
    );
  });

  group('the password recovery requests', () {
    test(
      'the OTP request POSTs the email as its only query parameter, with no body',
      () async {
        backend.on(
          'POST',
          '/api/auth/forgot-password',
          (_) => okResponse(null, message: 'Mã OTP đã được gửi'),
        );

        await RemoteApiHelper.instance.forgotPassword(email: 'An@Example.com');

        final request = backend.requests.single;
        expect(request.method, 'POST');
        expect(request.path, '/api/auth/forgot-password');
        expect(request.queryParameters, {'email': 'An@Example.com'});
        expect(request.data, isNull);
      },
    );

    test(
      'the reset POSTs email, OTP and new password in the JSON body and nothing in the URL',
      () async {
        backend.on(
          'POST',
          '/api/auth/reset-password',
          (_) => okResponse(null, message: 'Đổi mật khẩu thành công!'),
        );

        await RemoteApiHelper.instance.resetPassword(
          email: 'a@b.c',
          otp: '123456',
          newPassword: 'NewPass456',
        );

        final request = backend.requests.single;
        expect(request.method, 'POST');
        expect(request.path, '/api/auth/reset-password');
        expect(request.data, {
          'email': 'a@b.c',
          'otp': '123456',
          'newPassword': 'NewPass456',
        });
        expect(request.queryParameters, isEmpty);
        expect(request.uri.query, isEmpty);
      },
    );

    test(
      'a refusal reaches the caller as the DioException of the server answer',
      () async {
        backend.on(
          'POST',
          '/api/auth/reset-password',
          (_) => jsonResponse(400, {
            'code': 400,
            'message': 'Mã OTP không chính xác. Bạn còn 2 lần thử.',
          }),
        );

        await expectLater(
          RemoteApiHelper.instance.resetPassword(
            email: 'a@b.c',
            otp: '000000',
            newPassword: 'NewPass456',
          ),
          throwsA(
            isA<DioException>()
                .having((e) => e.response?.statusCode, 'status code', 400)
                .having(
                  (e) => (e.response?.data as Map)['message'],
                  'server message',
                  'Mã OTP không chính xác. Bạn còn 2 lần thử.',
                ),
          ),
        );
        expect(backend.calls, ['POST /api/auth/reset-password']);
      },
    );
  });
}
