import 'package:flutter/foundation.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:flutter_facebook_auth/flutter_facebook_auth.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'remote_api_helper.dart';

class SocialAuthService {
  static final SocialAuthService instance = SocialAuthService._();
  SocialAuthService._();

  /// Only tests replace it: a [GoogleSignIn] keeps the Futures of its earlier calls in fields, and inside the fake async
  /// zone of another test they never wake up, so a second sign-out in the same test file would hang.
  GoogleSignIn _google = GoogleSignIn(
    serverClientId: AppConfig.googleServerClientId,
    scopes: const ['email', 'profile'],
  );

  @visibleForTesting
  set googleSignIn(GoogleSignIn google) => _google = google;

  Future<Map<String, dynamic>> signInWithGoogle() async {
    GoogleSignInAccount? account = _google.currentUser;
    account ??= await _google.signInSilently();
    account ??= await _google.signIn();
    if (account == null) {
      throw Exception('Đã hủy đăng nhập Google');
    }
    final auth = await account.authentication;
    final idToken = auth.idToken;

    if (idToken == null || idToken.isEmpty) {
      throw Exception(
        'Không lấy được Google idToken (kiểm tra serverClientId)',
      );
    }

    return RemoteApiHelper.instance.socialLogin(
      provider: 'google',
      token: idToken,
      email: account.email,
      name: account.displayName,
    );
  }

  Future<Map<String, dynamic>> signInWithFacebook() async {
    final result = await FacebookAuth.instance.login(
      permissions: const ['email', 'public_profile'],
    );

    if (result.status != LoginStatus.success || result.accessToken == null) {
      throw Exception(
        'Đăng nhập Facebook thất bại: ${result.message ?? result.status}',
      );
    }

    final token = result.accessToken!.tokenString;
    final data = await FacebookAuth.instance.getUserData(fields: 'email,name');

    return RemoteApiHelper.instance.socialLogin(
      provider: 'facebook',
      token: token,
      email: data['email']?.toString(),
      name: data['name']?.toString(),
    );
  }

  Future<void> signOut() async {
    try {
      await _google.disconnect();
      await FacebookAuth.instance.logOut();
    } catch (_) {}
  }
}
