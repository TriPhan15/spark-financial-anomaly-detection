class DateHelper {
  static String formatTransactionDate(DateTime date) {
    final now = DateTime.now();

    final isSameDay =
        now.year == date.year && now.month == date.month && now.day == date.day;

    final yesterday = now.subtract(const Duration(days: 1));
    final isYesterday =
        yesterday.year == date.year &&
        yesterday.month == date.month &&
        yesterday.day == date.day;

    final timeString =
        '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';

    if (isSameDay) {
      return 'Hôm nay, $timeString';
    }

    if (isYesterday) {
      return 'Hôm qua, $timeString';
    }

    return '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}';
  }
}
