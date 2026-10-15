import 'package:finance_ai_app/models/user_model.dart';

abstract class AuthRepository {
  Future<UserModel?> login({required String email, required String password});

  Future<int> register(UserModel user);
}
