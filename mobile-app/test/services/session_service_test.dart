import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:finance_ai_app/services/session_service.dart';

import '../helpers/captured_logs.dart';

// The storage format under test, spelled out here instead of read from the service.
const String _sessionKey = 'auth_session';
const String _legacyUserIdKey = 'current_user_id';
const String _legacyTokenKey = 'auth_token';
const String _legacyRefreshTokenKey = 'refresh_token';
const List<String> _legacyKeys = [
  _legacyUserIdKey,
  _legacyTokenKey,
  _legacyRefreshTokenKey,
];

/// The real mocked storage, except that writing the session key can be made to fail, like a full disk or a platform
/// channel error would.
class _FlakyPreferences implements SharedPreferences {
  _FlakyPreferences(this._real);

  final SharedPreferences _real;
  bool failSessionWrites = false;

  @override
  Future<bool> setString(String key, String value) {
    if (failSessionWrites && key == _sessionKey) return Future.value(false);
    return _real.setString(key, value);
  }

  @override
  String? getString(String key) => _real.getString(key);

  @override
  int? getInt(String key) => _real.getInt(key);

  @override
  bool containsKey(String key) => _real.containsKey(key);

  @override
  Future<bool> remove(String key) => _real.remove(key);

  @override
  dynamic noSuchMethod(Invocation invocation) {
    throw UnimplementedError(
      '_FlakyPreferences does not fake ${invocation.memberName}',
    );
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _FlakyPreferences storage;

  Future<void> startWith(Map<String, Object> stored) async {
    SharedPreferences.setMockInitialValues(stored);
    storage = _FlakyPreferences(await SharedPreferences.getInstance());
    SessionService.preferences = () async => storage;
    await SessionService.init();
  }

  Map<String, dynamic>? storedSession() {
    final json = storage.getString(_sessionKey);
    return json == null ? null : jsonDecode(json) as Map<String, dynamic>;
  }

  Future<void> expectSignedInAs(
    int? userId,
    String? token,
    String? refresh,
  ) async {
    expect(SessionService.currentUserId, userId);
    expect(SessionService.currentToken, token);
    expect(SessionService.currentRefreshToken, refresh);
    expect(await SessionService.isLoggedIn(), userId != null);
  }

  tearDown(() async {
    storage.failSessionWrites = false;
    await SessionService.clearSession();
    SessionService.preferences = SharedPreferences.getInstance;
  });

  group('migrating a session of the old format', () {
    test(
      'moves the user id and both tokens into one JSON key, under "token" like the login answer, then drops the old keys',
      () async {
        await startWith({
          _legacyUserIdKey: 7,
          _legacyTokenKey: 'old-access',
          _legacyRefreshTokenKey: 'old-refresh',
        });

        await expectSignedInAs(7, 'old-access', 'old-refresh');
        expect(storedSession(), {
          'userId': 7,
          'token': 'old-access',
          'refreshToken': 'old-refresh',
        });
        for (final key in _legacyKeys) {
          expect(storage.containsKey(key), isFalse, reason: key);
        }
      },
    );

    test(
      'keeps a local-mode session that has a user id but no tokens',
      () async {
        await startWith({_legacyUserIdKey: 3});

        await expectSignedInAs(3, null, null);
        expect(storedSession(), {
          'userId': 3,
          'token': null,
          'refreshToken': null,
        });
      },
    );

    test(
      'when the JSON session cannot be written, keeps the old keys and the user stays signed in',
      () async {
        SharedPreferences.setMockInitialValues({
          _legacyUserIdKey: 7,
          _legacyTokenKey: 'old-access',
          _legacyRefreshTokenKey: 'old-refresh',
        });
        storage = _FlakyPreferences(await SharedPreferences.getInstance())
          ..failSessionWrites = true;
        SessionService.preferences = () async => storage;

        await withCapturedLogs((logs) async {
          await SessionService.init();
          expect(logs.single, contains('old keys stay'));
        });

        // Not signed out by the new format: the cache holds exactly what the old keys hold.
        await expectSignedInAs(7, 'old-access', 'old-refresh');
        expect(storedSession(), isNull);
        expect(storage.getInt(_legacyUserIdKey), 7);
        expect(storage.getString(_legacyTokenKey), 'old-access');
        expect(storage.getString(_legacyRefreshTokenKey), 'old-refresh');

        // The next start, with a storage that works again, finishes the move.
        storage.failSessionWrites = false;
        await SessionService.init();
        await expectSignedInAs(7, 'old-access', 'old-refresh');
        expect(storedSession(), isNotNull);
        expect(storage.containsKey(_legacyTokenKey), isFalse);
      },
    );

    test(
      'a JSON session wins over old keys an interrupted migration left behind, which are then removed',
      () async {
        await startWith({
          _sessionKey: jsonEncode({
            'userId': 7,
            'token': 'new-access',
            'refreshToken': 'new-refresh',
          }),
          _legacyTokenKey: 'stale-access',
          _legacyRefreshTokenKey: 'stale-refresh',
        });

        await expectSignedInAs(7, 'new-access', 'new-refresh');
        expect(storage.containsKey(_legacyTokenKey), isFalse);
        expect(storage.containsKey(_legacyRefreshTokenKey), isFalse);
      },
    );

    test('nothing stored in either format means signed out', () async {
      await startWith({});

      await expectSignedInAs(null, null, null);
      expect(storedSession(), isNull);
    });

    test(
      'an unreadable JSON session is ignored, not a crash of the start',
      () async {
        await startWith({_sessionKey: 'not json {'});

        await expectSignedInAs(null, null, null);
      },
    );
  });

  group('setTokens', () {
    test(
      'stores the pair together with the user id in the one JSON key, and the getters read it',
      () async {
        await startWith({});
        await SessionService.setCurrentUserId(7);

        await SessionService.setTokens('access-2', 'refresh-2');

        await expectSignedInAs(7, 'access-2', 'refresh-2');
        expect(storedSession(), {
          'userId': 7,
          'token': 'access-2',
          'refreshToken': 'refresh-2',
        });
        for (final key in _legacyKeys) {
          expect(storage.containsKey(key), isFalse, reason: key);
        }
      },
    );

    test(
      'replaces both tokens at once, the refresh token included, and keeps the user',
      () async {
        await startWith({
          _sessionKey: jsonEncode({
            'userId': 7,
            'token': 'access-1',
            'refreshToken': 'refresh-1',
          }),
        });

        await SessionService.setTokens('access-2', 'refresh-2');

        await expectSignedInAs(7, 'access-2', 'refresh-2');
        expect(storedSession()!['refreshToken'], 'refresh-2');
      },
    );

    test(
      'when the write fails, throws and leaves the stored and the cached pair as they were',
      () async {
        await startWith({
          _sessionKey: jsonEncode({
            'userId': 7,
            'token': 'access-1',
            'refreshToken': 'refresh-1',
          }),
        });
        storage.failSessionWrites = true;

        await withCapturedLogs((_) async {
          await expectLater(
            SessionService.setTokens('access-2', 'refresh-2'),
            throwsA(isA<SessionStorageException>()),
          );
        });

        // Never a new token in memory while the storage still has the old one.
        await expectSignedInAs(7, 'access-1', 'refresh-1');
        expect(storedSession()!['token'], 'access-1');
      },
    );
  });

  group('clearSession', () {
    test(
      'empties the cache and removes the JSON key and every old key',
      () async {
        await startWith({
          _sessionKey: jsonEncode({
            'userId': 7,
            'token': 'access-1',
            'refreshToken': 'refresh-1',
          }),
          _legacyTokenKey: 'stale-access',
        });

        await SessionService.clearSession();

        await expectSignedInAs(null, null, null);
        expect(storage.containsKey(_sessionKey), isFalse);
        for (final key in _legacyKeys) {
          expect(storage.containsKey(key), isFalse, reason: key);
        }
      },
    );
  });
}
