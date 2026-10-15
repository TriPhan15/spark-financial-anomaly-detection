import 'package:intl/intl.dart';

/// How the period of the spending report is chosen.
enum SpendingPeriod { week, month, custom }

/// One bar of the report chart: the days from [start] up to, but not including, [end].
class SpendingBucket {
  final String label;
  final DateTime start;
  final DateTime end;

  const SpendingBucket({
    required this.label,
    required this.start,
    required this.end,
  });

  /// Whether [date] is on one of the days of the bucket, whatever its time of day.
  bool contains(DateTime date) {
    final day = DateTime(date.year, date.month, date.day);
    return !day.isBefore(start) && day.isBefore(end);
  }
}

/// The days the spending report covers, the first and the last one included, and the bars its chart is split into.
class SpendingRange {
  /// The month keeps the four weekly bars the chart always had: days 1-7, 8-14, 15-21 and 22 up to the month's end.
  static const int monthBucketCount = 4;

  /// A week, or a range the user picked, is split into at most this many bars so that they stay readable.
  static const int maxFittedBuckets = 7;

  static const String _weekBucketPrefix = 'W';
  static const List<String> _weekdayLabels = [
    'T2',
    'T3',
    'T4',
    'T5',
    'T6',
    'T7',
    'CN',
  ];
  static final DateFormat _dayMonth = DateFormat('dd/MM');
  static final DateFormat _fullDate = DateFormat('dd/MM/yyyy');

  final SpendingPeriod period;

  /// First day of the range, at midnight.
  final DateTime start;

  /// Last day of the range, at midnight. The day itself belongs to the range.
  final DateTime end;

  const SpendingRange._(this.period, this.start, this.end);

  /// The Monday-to-Sunday week that contains [now], the same week the Ledger shows.
  factory SpendingRange.week(DateTime now) {
    final monday = DateTime(
      now.year,
      now.month,
      now.day - (now.weekday - DateTime.monday),
    );
    return SpendingRange._(
      SpendingPeriod.week,
      monday,
      _shift(monday, DateTime.daysPerWeek - 1),
    );
  }

  /// The calendar month that contains [now].
  factory SpendingRange.month(DateTime now) => SpendingRange._(
    SpendingPeriod.month,
    DateTime(now.year, now.month, 1),
    DateTime(now.year, now.month + 1, 0),
  );

  /// The days from [from] to [to], both included. They may come in either order and at any time of day.
  factory SpendingRange.custom(DateTime from, DateTime to) {
    final a = DateTime(from.year, from.month, from.day);
    final b = DateTime(to.year, to.month, to.day);
    return a.isAfter(b)
        ? SpendingRange._(SpendingPeriod.custom, b, a)
        : SpendingRange._(SpendingPeriod.custom, a, b);
  }

  /// The range of [period] as of [now]. [custom] is the range the user picked, which only [SpendingPeriod.custom] uses;
  /// without one the month is reported.
  factory SpendingRange.of(
    SpendingPeriod period,
    DateTime now, {
    SpendingRange? custom,
  }) {
    switch (period) {
      case SpendingPeriod.week:
        return SpendingRange.week(now);
      case SpendingPeriod.month:
        return SpendingRange.month(now);
      case SpendingPeriod.custom:
        return custom ?? SpendingRange.month(now);
    }
  }

  /// How many days the range has. Counted between UTC dates, so a daylight saving change cannot skew it.
  int get days =>
      DateTime.utc(
        end.year,
        end.month,
        end.day,
      ).difference(DateTime.utc(start.year, start.month, start.day)).inDays +
      1;

  /// The first and the last day as text, for example `21/09/2026 - 27/09/2026`.
  String get label => '${_fullDate.format(start)} - ${_fullDate.format(end)}';

  /// Whether [date] is on one of the days of the range, whatever its time of day.
  bool contains(DateTime date) {
    final day = DateTime(date.year, date.month, date.day);
    return !day.isBefore(start) && !day.isAfter(end);
  }

  /// The bars of the chart, in order, with no gap between them; together they cover exactly the days of the range.
  List<SpendingBucket> get buckets =>
      period == SpendingPeriod.month ? _weeklyBuckets() : _fittedBuckets();

  /// Four weeks counted from the first day; the last one takes whatever days are left.
  List<SpendingBucket> _weeklyBuckets() {
    return List.generate(monthBucketCount, (i) {
      final from = _shift(start, i * DateTime.daysPerWeek);
      final isLast = i == monthBucketCount - 1;
      return SpendingBucket(
        label: '$_weekBucketPrefix${i + 1}',
        start: from,
        end: isLast ? _shift(end, 1) : _shift(from, DateTime.daysPerWeek),
      );
    });
  }

  /// As few days per bar as it takes to stay within [maxFittedBuckets]. A bar of one day is named after its weekday, a
  /// longer one after its first day.
  List<SpendingBucket> _fittedBuckets() {
    final endExclusive = _shift(end, 1);
    final daysPerBucket = (days / maxFittedBuckets).ceil();
    final count = (days / daysPerBucket).ceil();
    return List.generate(count, (i) {
      final from = _shift(start, i * daysPerBucket);
      final to = _shift(from, daysPerBucket);
      return SpendingBucket(
        label: daysPerBucket == 1
            ? _weekdayLabels[from.weekday - DateTime.monday]
            : _dayMonth.format(from),
        start: from,
        end: to.isAfter(endExclusive) ? endExclusive : to,
      );
    });
  }

  static DateTime _shift(DateTime day, int days) =>
      DateTime(day.year, day.month, day.day + days);

  @override
  bool operator ==(Object other) =>
      other is SpendingRange &&
      other.period == period &&
      other.start == start &&
      other.end == end;

  @override
  int get hashCode => Object.hash(period, start, end);
}
