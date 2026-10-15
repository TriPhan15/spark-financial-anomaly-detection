import 'dart:io';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:finance_ai_app/models/user_model.dart';
import 'package:finance_ai_app/models/sync_status.dart';
import 'package:finance_ai_app/models/transaction_model.dart';
import 'package:finance_ai_app/models/category_model.dart';
import 'package:finance_ai_app/models/wallet_model.dart';
import 'package:finance_ai_app/models/budget_model.dart';

class SqfliteHelper {
  SqfliteHelper._();

  static final SqfliteHelper instance = SqfliteHelper._();

  static const String _dbName = 'finance_ai_app.db';
  static const int _dbVersion = 6;

  static const String usersTable = 'users';
  static const String transactionsTable = 'transactions';
  static const String categoriesTable = 'categories';
  static const String walletsTable = 'wallets';
  static const String budgetsTable = 'budgets';

  /// Column definitions of the sync state of a transaction (version 6). Rows that already exist count as synced.
  static const String _clientIdColumnDef =
      '${TransactionModel.columnClientId} TEXT';
  static final String _syncStatusColumnDef =
      "${TransactionModel.columnSyncStatus} TEXT NOT NULL DEFAULT '${SyncStatus.synced.dbValue}'";

  Database? _database;

  Future<Database> get database async {
    final cached = _database;
    if (cached != null) return cached;

    _database = await _open();
    return _database!;
  }

  Future<Database> _open() async {
    if (Platform.isWindows || Platform.isLinux || Platform.isMacOS) {
      sqfliteFfiInit();
      databaseFactory = databaseFactoryFfi;
    }

    final dbPath = await getDatabasesPath();
    final path = p.join(dbPath, _dbName);

    return openDatabase(
      path,
      version: _dbVersion,
      onCreate: (db, version) async {
        await db.execute('''
CREATE TABLE $usersTable (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT NOT NULL UNIQUE,
  password TEXT NOT NULL,
  full_name TEXT NOT NULL,
  birthdate TEXT,
  phone TEXT,
  address TEXT
)
''');

        await db.execute('''
CREATE TABLE $transactionsTable (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  title TEXT NOT NULL,
  category TEXT NOT NULL,
  amount REAL NOT NULL,
  date TEXT NOT NULL,
  is_expense INTEGER NOT NULL,
  icon_name TEXT NOT NULL DEFAULT 'default',
  wallet_id INTEGER,
  $_clientIdColumnDef,
  $_syncStatusColumnDef
)
''');

        await db.execute('''
CREATE TABLE $categoriesTable (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  icon_name TEXT NOT NULL,
  color_value INTEGER NOT NULL,
  type INTEGER NOT NULL,
  user_id INTEGER
)
''');

        await db.execute('''
CREATE TABLE $walletsTable (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  balance REAL NOT NULL DEFAULT 0,
  color_value INTEGER NOT NULL,
  icon_name TEXT NOT NULL,
  user_id INTEGER
)
''');

        await db.execute('''
CREATE TABLE $budgetsTable (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  category_id INTEGER NOT NULL,
  category_name TEXT NOT NULL,
  limit_amount REAL NOT NULL,
  month INTEGER NOT NULL,
  year INTEGER NOT NULL,
  user_id INTEGER
)
''');
      },
      onUpgrade: (db, oldVersion, newVersion) async {
        if (oldVersion < 2) {
          await db.execute('''
CREATE TABLE IF NOT EXISTS $transactionsTable (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  title TEXT NOT NULL,
  category TEXT NOT NULL,
  amount REAL NOT NULL,
  date TEXT NOT NULL,
  is_expense INTEGER NOT NULL,
  icon_name TEXT NOT NULL DEFAULT 'default',
  wallet_id INTEGER
)
''');
        }
        if (oldVersion < 3) {
          await db.execute('''
CREATE TABLE IF NOT EXISTS $budgetsTable (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  category_id INTEGER NOT NULL,
  category_name TEXT NOT NULL,
  limit_amount REAL NOT NULL,
  month INTEGER NOT NULL,
  year INTEGER NOT NULL,
  user_id INTEGER
)
''');
        }

        if (oldVersion < 4) {
          final columns = await db.rawQuery(
            'PRAGMA table_info($transactionsTable)',
          );
          final hasIconName = columns.any((c) => c['name'] == 'icon_name');
          if (!hasIconName) {
            await db.execute(
              "ALTER TABLE $transactionsTable ADD COLUMN icon_name TEXT NOT NULL DEFAULT 'default'",
            );
          }
        }

        if (oldVersion < 5) {
          final columns = await db.rawQuery('PRAGMA table_info($usersTable)');
          final hasAddress = columns.any((c) => c['name'] == 'address');
          if (!hasAddress) {
            await db.execute('ALTER TABLE $usersTable ADD COLUMN address TEXT');
          }
        }

        if (oldVersion < 6) {
          final columns = await db.rawQuery(
            'PRAGMA table_info($transactionsTable)',
          );
          bool hasColumn(String name) => columns.any((c) => c['name'] == name);
          if (!hasColumn(TransactionModel.columnClientId)) {
            await db.execute(
              'ALTER TABLE $transactionsTable ADD COLUMN $_clientIdColumnDef',
            );
          }
          if (!hasColumn(TransactionModel.columnSyncStatus)) {
            await db.execute(
              'ALTER TABLE $transactionsTable ADD COLUMN $_syncStatusColumnDef',
            );
          }
        }
      },
    );
  }

  Future<int> insertUser(UserModel user) async {
    final db = await database;
    return db.insert(
      usersTable,
      user.toMap(),
      conflictAlgorithm: ConflictAlgorithm.abort,
    );
  }

  Future<void> upsertUserWithId(UserModel user) async {
    final db = await database;
    final existing = await db.query(
      usersTable,
      where: 'id = ?',
      whereArgs: [user.id],
      limit: 1,
    );
    final map = user.toMap()..['id'] = user.id;
    if (existing.isNotEmpty) {
      final old = UserModel.fromMap(existing.first);
      if ((map['full_name'] as String).isEmpty) map['full_name'] = old.fullName;
      if (map['phone'] == null) map['phone'] = old.phone;
      if (map['birthdate'] == null) map['birthdate'] = old.birthdate;
      if (map['address'] == null) map['address'] = old.address;
    }
    await db.insert(
      usersTable,
      map,
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<int> updateUser(UserModel user) async {
    final id = user.id;
    if (id == null) {
      throw ArgumentError('UserModel.id is required for update');
    }

    final db = await database;
    return db.update(
      usersTable,
      user.toMap(),
      where: 'id = ?',
      whereArgs: [id],
    );
  }

  Future<UserModel?> getUserById(int id) async {
    final db = await database;
    final rows = await db.query(
      usersTable,
      where: 'id = ?',
      whereArgs: [id],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return UserModel.fromMap(rows.first);
  }

  Future<UserModel?> getUserByEmail(String email) async {
    final db = await database;
    final rows = await db.query(
      usersTable,
      where: 'email = ?',
      whereArgs: [email],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return UserModel.fromMap(rows.first);
  }

  Future<UserModel?> getUserByEmailAndPassword({
    required String email,
    required String password,
  }) async {
    final db = await database;
    final rows = await db.query(
      usersTable,
      where: 'email = ? AND password = ?',
      whereArgs: [email, password],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return UserModel.fromMap(rows.first);
  }

  Future<int> insertTransaction(
    TransactionModel transaction,
    int userId,
  ) async {
    final db = await database;
    return db.insert(
      transactionsTable,
      transaction.toDbMap(userId),
      conflictAlgorithm: ConflictAlgorithm.abort,
    );
  }

  Future<List<TransactionModel>> getTransactionsByUserId(int userId) async {
    final db = await database;
    final rows = await db.query(
      transactionsTable,
      where: 'user_id = ?',
      whereArgs: [userId],
      orderBy: 'date DESC',
    );
    return rows.map(TransactionModel.fromDbMap).toList();
  }

  /// The transactions of the user that the server has not confirmed (waiting to be sent, or failed), newest first.
  Future<List<TransactionModel>> getUnsyncedTransactions(int userId) async {
    final db = await database;
    final rows = await db.query(
      transactionsTable,
      where: 'user_id = ? AND ${TransactionModel.columnSyncStatus} != ?',
      whereArgs: [userId, SyncStatus.synced.dbValue],
      orderBy: 'date DESC',
    );
    return rows.map(TransactionModel.fromDbMap).toList();
  }

  /// Moves the transaction of the user with this clientId to [status]. Returns how many rows changed.
  Future<int> updateTransactionSyncStatus(
    int userId,
    String clientId,
    SyncStatus status,
  ) async {
    final db = await database;
    return db.update(
      transactionsTable,
      {TransactionModel.columnSyncStatus: status.dbValue},
      where: 'user_id = ? AND ${TransactionModel.columnClientId} = ?',
      whereArgs: [userId, clientId],
    );
  }

  Future<int> deleteTransactionByClientId(int userId, String clientId) async {
    final db = await database;
    return db.delete(
      transactionsTable,
      where: 'user_id = ? AND ${TransactionModel.columnClientId} = ?',
      whereArgs: [userId, clientId],
    );
  }

  Future<int> insertCategory(CategoryModel category) async {
    final db = await database;
    return db.insert(categoriesTable, category.toMap());
  }

  Future<List<CategoryModel>> getCategoriesByUserId(int userId) async {
    final db = await database;
    final rows = await db.query(
      categoriesTable,
      where: 'user_id = ? OR user_id IS NULL',
      whereArgs: [userId],
    );
    return rows.map(CategoryModel.fromMap).toList();
  }

  Future<int> deleteCategory(int id) async {
    final db = await database;
    return db.delete(categoriesTable, where: 'id = ?', whereArgs: [id]);
  }

  Future<int> insertWallet(WalletModel wallet) async {
    final db = await database;
    return db.insert(walletsTable, wallet.toMap());
  }

  Future<List<WalletModel>> getWalletsByUserId(int userId) async {
    final db = await database;
    final rows = await db.query(
      walletsTable,
      where: 'user_id = ?',
      whereArgs: [userId],
    );
    return rows.map(WalletModel.fromMap).toList();
  }

  Future<int> updateWalletBalance(int walletId, double newBalance) async {
    final db = await database;
    return db.update(
      walletsTable,
      {'balance': newBalance},
      where: 'id = ?',
      whereArgs: [walletId],
    );
  }

  Future<int> deleteWallet(int id) async {
    final db = await database;
    return db.delete(walletsTable, where: 'id = ?', whereArgs: [id]);
  }

  Future<int> insertBudget(BudgetModel budget) async {
    final db = await database;
    return db.insert(budgetsTable, budget.toMap());
  }

  Future<int> updateBudget(BudgetModel budget) async {
    final db = await database;
    return db.update(
      budgetsTable,
      budget.toMap(),
      where: 'id = ?',
      whereArgs: [budget.id],
    );
  }

  Future<List<BudgetModel>> getBudgetsByUserId(int userId) async {
    final db = await database;
    final rows = await db.query(
      budgetsTable,
      where: 'user_id = ?',
      whereArgs: [userId],
      orderBy: 'year DESC, month DESC, id DESC',
    );
    return rows.map(BudgetModel.fromMap).toList();
  }

  Future<List<BudgetModel>> getBudgetsByUserIdAndMonth(
    int userId,
    int month,
    int year,
  ) async {
    final db = await database;
    final rows = await db.query(
      budgetsTable,
      where: 'user_id = ? AND month = ? AND year = ?',
      whereArgs: [userId, month, year],
    );
    return rows.map(BudgetModel.fromMap).toList();
  }

  Future<int> deleteBudget(int id) async {
    final db = await database;
    return db.delete(budgetsTable, where: 'id = ?', whereArgs: [id]);
  }
}
