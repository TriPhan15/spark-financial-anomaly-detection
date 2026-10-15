/// Values of the backend's BudgetPeriodType (core-backend/.../Entity/BudgetPeriodType.java) and the date ranges the
/// app derives from them. The backend accepts a range of at most 7 days for WEEK and 31 days for MONTH.
class BudgetPeriodType {
  static const String week = 'WEEK';
  static const String month = 'MONTH';
  static const List<String> values = [week, month];

  static String label(String periodType) =>
      periodType == week ? 'Tuần' : 'Tháng';

  /// The calendar week (Monday to Sunday) or month that contains [day].
  static ({DateTime start, DateTime end}) rangeContaining(
    String periodType,
    DateTime day,
  ) {
    if (periodType == week) {
      final monday = DateTime(
        day.year,
        day.month,
        day.day - (day.weekday - DateTime.monday),
      );
      return (
        start: monday,
        end: DateTime(monday.year, monday.month, monday.day + 6),
      );
    }
    return (
      start: DateTime(day.year, day.month, 1),
      end: DateTime(day.year, day.month + 1, 0),
    );
  }
}

/// A category a budget can be limited to.
typedef BudgetCategory = ({int id, String name});

/// A spending limit, as returned by GET /api/budgets. A null category means the limit covers every category.
/// The local SQLite copy (offline mode) only knows months, see [fromMap] and [toMap].
class BudgetModel {
  /// What a budget without a category is called on screen.
  static const String allCategoriesLabel = 'Tất cả danh mục';

  /// The local `budgets` table has NOT NULL category columns, so "every category" is stored under this id there.
  static const int allCategoriesLocalId = 0;

  final int? id;
  final int? categoryId;
  final String? categoryName;
  final double amount;
  final String periodType;

  /// Both dates are yyyy-MM-dd, exactly as the backend sends and expects them.
  final String startDate;
  final String endDate;

  /// Only used by the local SQLite copy; the backend takes the owner from the login token.
  final int? userId;

  const BudgetModel({
    this.id,
    this.categoryId,
    this.categoryName,
    required this.amount,
    required this.periodType,
    required this.startDate,
    required this.endDate,
    this.userId,
  });

  /// A budget for the week or month of [periodType] that contains [day]; the dates are computed, never typed.
  factory BudgetModel.forPeriod({
    int? id,
    int? categoryId,
    String? categoryName,
    required double amount,
    required String periodType,
    required DateTime day,
    int? userId,
  }) {
    final range = BudgetPeriodType.rangeContaining(periodType, day);
    return BudgetModel(
      id: id,
      categoryId: categoryId,
      categoryName: categoryName,
      amount: amount,
      periodType: periodType,
      startDate: formatApiDate(range.start),
      endDate: formatApiDate(range.end),
      userId: userId,
    );
  }

  static String formatApiDate(DateTime date) =>
      '${date.year.toString().padLeft(4, '0')}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';

  DateTime get start => DateTime.parse(startDate);

  DateTime get end => DateTime.parse(endDate);

  /// Whether [day] is inside the period of the budget; its first and its last day both count.
  bool coversDay(DateTime day) {
    final date = DateTime(day.year, day.month, day.day);
    return !date.isBefore(start) && !date.isAfter(end);
  }

  /// Whether the period of the budget shares at least one day with [from]..[to], both days included.
  bool overlaps(DateTime from, DateTime to) {
    final first = DateTime(from.year, from.month, from.day);
    final last = DateTime(to.year, to.month, to.day);
    return !end.isBefore(first) && !start.isAfter(last);
  }

  factory BudgetModel.fromJson(Map<String, dynamic> json) {
    return BudgetModel(
      id: (json['id'] as num?)?.toInt(),
      categoryId: (json['categoryId'] as num?)?.toInt(),
      categoryName: json['categoryName'] as String?,
      amount: (json['amount'] as num).toDouble(),
      periodType: json['periodType'] as String,
      startDate: json['startDate'] as String,
      endDate: json['endDate'] as String,
    );
  }

  /// The body of POST and PUT /api/budgets. It carries no user: the backend always uses the caller.
  Map<String, dynamic> toJson() {
    return {
      'categoryId': categoryId,
      'amount': amount,
      'periodType': periodType,
      'startDate': startDate,
      'endDate': endDate,
    };
  }

  /// A row of the local `budgets` table, which only stores monthly budgets.
  factory BudgetModel.fromMap(Map<String, dynamic> map) {
    final now = DateTime.now();
    final localCategoryId = map['category_id'] as int? ?? allCategoriesLocalId;
    final isAllCategories = localCategoryId == allCategoriesLocalId;
    final month = DateTime(
      map['year'] as int? ?? now.year,
      map['month'] as int? ?? now.month,
    );
    return BudgetModel.forPeriod(
      id: map['id'] as int?,
      categoryId: isAllCategories ? null : localCategoryId,
      categoryName: isAllCategories ? null : map['category_name'] as String?,
      amount: (map['limit_amount'] as num?)?.toDouble() ?? 0.0,
      periodType: BudgetPeriodType.month,
      day: month,
      userId: map['user_id'] as int?,
    );
  }

  Map<String, dynamic> toMap() {
    final firstDay = start;
    return {
      if (id != null) 'id': id,
      'category_id': categoryId ?? allCategoriesLocalId,
      'category_name': categoryName ?? '',
      'limit_amount': amount,
      'month': firstDay.month,
      'year': firstDay.year,
      'user_id': userId,
    };
  }

  BudgetModel copyWith({
    int? id,
    int? categoryId,
    String? categoryName,
    bool clearCategory = false,
    double? amount,
    String? periodType,
    String? startDate,
    String? endDate,
    int? userId,
  }) {
    return BudgetModel(
      id: id ?? this.id,
      categoryId: clearCategory ? null : categoryId ?? this.categoryId,
      categoryName: clearCategory ? null : categoryName ?? this.categoryName,
      amount: amount ?? this.amount,
      periodType: periodType ?? this.periodType,
      startDate: startDate ?? this.startDate,
      endDate: endDate ?? this.endDate,
      userId: userId ?? this.userId,
    );
  }
}
