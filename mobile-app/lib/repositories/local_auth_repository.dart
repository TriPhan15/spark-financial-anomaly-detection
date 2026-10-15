import 'package:finance_ai_app/models/user_model.dart';
import 'package:finance_ai_app/repositories/auth_repository.dart';
import 'package:finance_ai_app/services/sqflite_helper.dart';

class LocalAuthRepository implements AuthRepository {
  final SqfliteHelper _db;

  const LocalAuthRepository._(this._db);

  static final LocalAuthRepository instance = LocalAuthRepository._(
    SqfliteHelper.instance,
  );

  @override
  Future<UserModel?> login({required String email, required String password}) {
    return _db.getUserByEmailAndPassword(email: email, password: password);
  }

  @override
  Future<int> register(UserModel user) {
    return _db.insertUser(user);
  }
}
