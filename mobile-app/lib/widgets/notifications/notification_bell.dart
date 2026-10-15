import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'package:finance_ai_app/core/extensions/build_context_extension.dart';

/// The bell of the home screen with the number of unread notifications on it. It listens to [unreadCount] itself, so
/// a new count repaints the bell and nothing around it.
class NotificationBell extends StatelessWidget {
  const NotificationBell({
    super.key,
    required this.unreadCount,
    required this.onPressed,
  });

  /// Above this the badge stops counting and shows "99+".
  static const int maxShownCount = 99;

  final ValueListenable<int> unreadCount;
  final VoidCallback onPressed;

  /// The text of the badge: null (no badge) when nothing is unread, the number up to [maxShownCount], "99+" above.
  static String? badgeLabel(int count) {
    if (count <= 0) return null;
    return count > maxShownCount ? '$maxShownCount+' : '$count';
  }

  @override
  Widget build(BuildContext context) {
    final tooltip = context.loc.notificationsTitle;
    // The badge takes its colours (error / onError) from the theme.
    return ValueListenableBuilder<int>(
      valueListenable: unreadCount,
      child: Icon(
        Icons.notifications_outlined,
        color: Theme.of(context).colorScheme.onSurface,
      ),
      builder: (context, count, icon) {
        final label = badgeLabel(count);
        return IconButton(
          tooltip: tooltip,
          onPressed: onPressed,
          icon: Badge(
            isLabelVisible: label != null,
            label: label == null ? null : Text(label),
            child: icon,
          ),
        );
      },
    );
  }
}
