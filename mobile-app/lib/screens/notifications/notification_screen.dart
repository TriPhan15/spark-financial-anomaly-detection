import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/extensions/build_context_extension.dart';
import 'package:finance_ai_app/l10n/app_localizations.dart';
import 'package:finance_ai_app/models/app_notification.dart';
import 'package:finance_ai_app/screens/notifications/notification_destination.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';

extension NotificationTypeLabels on AppLocalizations {
  String notificationTypeLabel(NotificationType type) => switch (type) {
    NotificationType.budgetWarning => notificationTypeBudgetWarning,
    NotificationType.budgetExceeded => notificationTypeBudgetExceeded,
    NotificationType.transactionAlert => notificationTypeTransactionAlert,
    NotificationType.receiptProcessed => notificationTypeReceiptProcessed,
    NotificationType.unknown => notificationTypeOther,
  };
}

/// The user's notifications, newest first. Pull down to reload; the next page is asked for near the end of the list;
/// a tap shows the notification as read at once, tells the server in the background (and shows it unread again if that
/// fails), then opens its screen when the app has one ([NotificationDestination]).
/// Notifications live on the server only: without a backend (demo mode) the screen is empty and requests nothing.
class NotificationScreen extends StatefulWidget {
  const NotificationScreen({super.key});

  /// How many notifications one request asks for; the server accepts 1 to 50.
  static const int pageSize = 20;

  @override
  State<NotificationScreen> createState() => _NotificationScreenState();
}

class _NotificationScreenState extends State<NotificationScreen> {
  /// How close to the end of the list, in pixels, the next page is asked for.
  static const double _loadMoreExtent = 300;

  final ScrollController _scroll = ScrollController();

  List<AppNotification> _items = const [];
  int _nextPage = 1;
  bool _hasNext = false;
  bool _loadingFirstPage = true;
  bool _firstPageFailed = false;
  bool _loadingMore = false;
  bool _loadMoreFailed = false;
  bool _markingAll = false;

  /// Ids whose "mark as read" request is still running, so a second tap sends no second request.
  final Set<int> _markingRead = {};

  /// Bumped by every load of the first page: whatever answers a request made before it is dropped, not mixed in.
  int _generation = 0;

  bool get _hasUnread => _items.any((notification) => !notification.isRead);

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    if (AppConfig.isFull) {
      _reload();
    } else {
      _loadingFirstPage = false;
    }
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  /// Loads the first page and replaces the list: on opening, on pull-to-refresh and on "try again".
  Future<void> _reload() async {
    if (!AppConfig.isFull) return;
    final generation = ++_generation;
    try {
      final page = await RemoteApiHelper.instance.getNotifications(
        page: 0,
        size: NotificationScreen.pageSize,
      );
      if (!mounted || generation != _generation) return;
      setState(() {
        _items = page.content;
        _nextPage = 1;
        _hasNext = page.hasNext;
        _loadingFirstPage = false;
        _firstPageFailed = false;
        _loadingMore = false;
        _loadMoreFailed = false;
      });
      _loadMoreIfTheListIsShort();
    } on DioException catch (e) {
      _onReloadFailed(generation, _describe(e));
    } on FormatException catch (e) {
      _onReloadFailed(generation, e.message);
    }
  }

  void _onReloadFailed(int generation, String reason) {
    debugPrint('Notifications: the first page could not be loaded ($reason).');
    if (!mounted || generation != _generation) return;
    final keepsAList = _items.isNotEmpty;
    setState(() {
      _loadingFirstPage = false;
      _firstPageFailed = !keepsAList;
      _loadingMore = false;
    });
    // A list is already on screen: it stays, and a short note says the reload failed.
    if (keepsAList) _showSnackBar(context.loc.notificationsLoadFailed);
  }

  void _retryFirstPage() {
    setState(() {
      _loadingFirstPage = true;
      _firstPageFailed = false;
    });
    _reload();
  }

  void _onScroll() {
    if (_scroll.position.extentAfter < _loadMoreExtent) _loadMore();
  }

  /// A list shorter than the screen cannot be scrolled, so the next page is asked for as soon as a page is shown.
  void _loadMoreIfTheListIsShort() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      if (_scroll.position.extentAfter < _loadMoreExtent) _loadMore();
    });
  }

  /// After a failure the next page is only asked for again from the "try again" button ([retry]), never by scrolling.
  Future<void> _loadMore({bool retry = false}) async {
    if (_loadingFirstPage || _loadingMore || !_hasNext) return;
    if (_loadMoreFailed && !retry) return;
    final generation = _generation;
    final pageNumber = _nextPage;
    setState(() {
      _loadingMore = true;
      _loadMoreFailed = false;
    });
    try {
      final page = await RemoteApiHelper.instance.getNotifications(
        page: pageNumber,
        size: NotificationScreen.pageSize,
      );
      if (!mounted || generation != _generation) return;
      setState(() {
        // A notification raised since the first page pushes the others one place down, so a page can repeat the last
        // ones already shown: those are skipped.
        final shown = {for (final notification in _items) notification.id};
        _items = [
          ..._items,
          ...page.content.where((notification) => shown.add(notification.id)),
        ];
        _nextPage = pageNumber + 1;
        _hasNext = page.hasNext;
        _loadingMore = false;
      });
      _loadMoreIfTheListIsShort();
    } on DioException catch (e) {
      _onLoadMoreFailed(generation, pageNumber, _describe(e));
    } on FormatException catch (e) {
      _onLoadMoreFailed(generation, pageNumber, e.message);
    }
  }

  void _onLoadMoreFailed(int generation, int pageNumber, String reason) {
    debugPrint(
      'Notifications: page $pageNumber could not be loaded ($reason).',
    );
    if (!mounted || generation != _generation) return;
    setState(() {
      _loadingMore = false;
      _loadMoreFailed = true;
    });
  }

  void _open(AppNotification notification) {
    if (!notification.isRead) unawaited(_markAsRead(notification));
    final destination = NotificationDestination.of(notification);
    if (destination == null) {
      _showSnackBar(context.loc.notificationsDetailUnavailable);
      return;
    }
    Navigator.push(context, MaterialPageRoute(builder: destination));
  }

  /// Optimistic: [notification] shows as read at once and goes back to unread if the server does not confirm.
  Future<void> _markAsRead(AppNotification notification) async {
    if (!_markingRead.add(notification.id)) return;
    final generation = _generation;
    _replace(notification.copyWith(isRead: true));
    try {
      final saved = await RemoteApiHelper.instance.markNotificationRead(
        notification.id,
      );
      if (mounted && generation == _generation) _replace(saved);
    } on DioException catch (e) {
      debugPrint(
        'Notifications: ${notification.id} could not be marked as read (${_describe(e)}).',
      );
      if (!mounted) return;
      if (generation == _generation) _replace(notification);
      _showSnackBar(context.loc.notificationsMarkReadFailed);
    } on FormatException catch (e) {
      // The server answered 2xx, so it did mark the notification: only its copy could not be read. It stays read.
      debugPrint(
        'Notifications: unreadable answer after marking ${notification.id} as read (${e.message}).',
      );
    } finally {
      _markingRead.remove(notification.id);
    }
  }

  /// Optimistic too: everything on screen shows as read at once; what was unread is put back if the server refuses.
  Future<void> _markAllAsRead() async {
    if (_markingAll || !_hasUnread) return;
    final generation = _generation;
    final wereUnread = {
      for (final notification in _items)
        if (!notification.isRead) notification.id,
    };
    setState(() {
      _markingAll = true;
      _items = [
        for (final notification in _items)
          notification.isRead
              ? notification
              : notification.copyWith(isRead: true),
      ];
    });
    try {
      await RemoteApiHelper.instance.markAllNotificationsRead();
    } on DioException catch (e) {
      debugPrint(
        'Notifications: marking all as read failed (${_describe(e)}).',
      );
      if (mounted) {
        if (generation == _generation) _markUnreadAgain(wereUnread);
        _showSnackBar(context.loc.notificationsMarkAllReadFailed);
      }
    } on FormatException catch (e) {
      // 2xx: the server marked them; only the count in its answer could not be read.
      debugPrint(
        'Notifications: unreadable answer after marking all as read (${e.message}).',
      );
    } finally {
      if (mounted) setState(() => _markingAll = false);
    }
  }

  void _markUnreadAgain(Set<int> ids) {
    setState(() {
      _items = [
        for (final notification in _items)
          ids.contains(notification.id)
              ? notification.copyWith(isRead: false)
              : notification,
      ];
    });
  }

  /// Puts [notification] in the place of the one with the same id, when that one is still listed.
  void _replace(AppNotification notification) {
    final index = _items.indexWhere((shown) => shown.id == notification.id);
    if (index < 0) return;
    setState(() {
      _items = List.of(_items)..[index] = notification;
    });
  }

  void _showSnackBar(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  static String _describe(DioException e) =>
      'HTTP ${e.response?.statusCode ?? e.type.name}';

  @override
  Widget build(BuildContext context) {
    final loc = context.loc;
    final Widget body;
    if (_loadingFirstPage) {
      // The indicator takes the primary colour of the theme.
      body = const Center(child: CircularProgressIndicator());
    } else if (_firstPageFailed) {
      body = _MessageView(
        icon: Icons.cloud_off_rounded,
        message: loc.notificationsLoadFailed,
        actionLabel: loc.notificationsRetry,
        onAction: _retryFirstPage,
      );
    } else if (_items.isEmpty) {
      body = _MessageView(
        icon: Icons.notifications_off_outlined,
        message: loc.notificationsEmpty,
      );
    } else {
      body = _NotificationList(
        controller: _scroll,
        notifications: _items,
        footer: _loadingMore
            ? _FooterState.loading
            : (_loadMoreFailed ? _FooterState.failed : _FooterState.idle),
        onTap: _open,
        onRetryMore: () => _loadMore(retry: true),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: Text(loc.notificationsTitle),
        actions: [
          TextButton(
            onPressed: _hasUnread && !_markingAll ? _markAllAsRead : null,
            child: Text(loc.notificationsMarkAllRead),
          ),
        ],
      ),
      body: SafeArea(
        child: RefreshIndicator(onRefresh: _reload, child: body),
      ),
    );
  }
}

enum _FooterState { idle, loading, failed }

class _NotificationList extends StatelessWidget {
  const _NotificationList({
    required this.controller,
    required this.notifications,
    required this.footer,
    required this.onTap,
    required this.onRetryMore,
  });

  final ScrollController controller;
  final List<AppNotification> notifications;
  final _FooterState footer;
  final ValueChanged<AppNotification> onTap;
  final VoidCallback onRetryMore;

  @override
  Widget build(BuildContext context) {
    // Colours, styles and the date format are worked out once here and handed to every row.
    final palette = _TilePalette(
      Theme.of(context),
      context.loc,
      Localizations.localeOf(context).toLanguageTag(),
    );
    return ListView.builder(
      controller: controller,
      physics: const AlwaysScrollableScrollPhysics(),
      itemCount: notifications.length + 1,
      itemBuilder: (context, index) => index < notifications.length
          ? _NotificationTile(
              notification: notifications[index],
              palette: palette,
              onTap: onTap,
            )
          : _ListFooter(state: footer, onRetry: onRetryMore),
    );
  }
}

/// What every row of one list build shares, read from the theme once.
class _TilePalette {
  _TilePalette(ThemeData theme, this.loc, String locale)
    : _colors = theme.colorScheme,
      _warning = theme.semanticColors.warningColor,
      _success = theme.semanticColors.successColor,
      unreadBackground = theme.colorScheme.primaryContainer.withValues(
        alpha: 0.4,
      ),
      unreadDot = theme.colorScheme.primary,
      unreadTitle = TextStyle(
        color: theme.colorScheme.onSurface,
        fontWeight: FontWeight.bold,
      ),
      readTitle = TextStyle(color: theme.colorScheme.onSurface),
      message = TextStyle(color: theme.colorScheme.onSurfaceVariant),
      time = TextStyle(color: theme.colorScheme.onSurfaceVariant, fontSize: 12),
      dateFormat = DateFormat.yMd(locale).add_Hm();

  final ColorScheme _colors;
  final Color _warning;
  final Color _success;
  final AppLocalizations loc;
  final Color unreadBackground;
  final Color unreadDot;
  final TextStyle unreadTitle;
  final TextStyle readTitle;
  final TextStyle message;
  final TextStyle time;
  final DateFormat dateFormat;

  Color accentOf(NotificationType type) => switch (type) {
    NotificationType.budgetWarning => _warning,
    NotificationType.budgetExceeded => _colors.error,
    NotificationType.transactionAlert => _colors.primary,
    NotificationType.receiptProcessed => _success,
    NotificationType.unknown => _colors.onSurfaceVariant,
  };

  static IconData iconOf(NotificationType type) => switch (type) {
    NotificationType.budgetWarning => Icons.warning_amber_rounded,
    NotificationType.budgetExceeded => Icons.error_outline_rounded,
    NotificationType.transactionAlert => Icons.swap_horiz_rounded,
    NotificationType.receiptProcessed => Icons.receipt_long_rounded,
    NotificationType.unknown => Icons.notifications_rounded,
  };
}

class _NotificationTile extends StatelessWidget {
  const _NotificationTile({
    required this.notification,
    required this.palette,
    required this.onTap,
  });

  /// Diameter of the dot that marks an unread notification.
  static const double _dotSize = 10;

  final AppNotification notification;
  final _TilePalette palette;
  final ValueChanged<AppNotification> onTap;

  @override
  Widget build(BuildContext context) {
    final unread = !notification.isRead;
    final accent = palette.accentOf(notification.type);
    final createdAt = notification.createdAt;
    return ListTile(
      onTap: () => onTap(notification),
      tileColor: unread ? palette.unreadBackground : null,
      leading: CircleAvatar(
        backgroundColor: accent.withValues(alpha: 0.15),
        child: Icon(_TilePalette.iconOf(notification.type), color: accent),
      ),
      title: Text(
        palette.loc.notificationTypeLabel(notification.type),
        style: unread ? palette.unreadTitle : palette.readTitle,
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(notification.message, style: palette.message),
          if (createdAt != null) ...[
            const SizedBox(height: 4),
            Text(palette.dateFormat.format(createdAt), style: palette.time),
          ],
        ],
      ),
      trailing: unread
          ? Semantics(
              label: palette.loc.notificationsUnread,
              child: Container(
                width: _dotSize,
                height: _dotSize,
                decoration: BoxDecoration(
                  color: palette.unreadDot,
                  shape: BoxShape.circle,
                ),
              ),
            )
          : null,
    );
  }
}

class _ListFooter extends StatelessWidget {
  const _ListFooter({required this.state, required this.onRetry});

  final _FooterState state;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    switch (state) {
      case _FooterState.idle:
        return const SizedBox(height: 24);
      case _FooterState.loading:
        return const Padding(
          padding: EdgeInsets.all(16),
          child: Center(child: CircularProgressIndicator()),
        );
      case _FooterState.failed:
        final loc = context.loc;
        return Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            children: [
              Text(
                loc.notificationsLoadMoreFailed,
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
              TextButton(
                onPressed: onRetry,
                child: Text(loc.notificationsRetry),
              ),
            ],
          ),
        );
    }
  }
}

/// A message in place of the list (nothing yet, or the list could not be loaded). It scrolls, so pulling it down
/// reloads like the list does.
class _MessageView extends StatelessWidget {
  const _MessageView({
    required this.icon,
    required this.message,
    this.actionLabel,
    this.onAction,
  });

  final IconData icon;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final label = actionLabel;
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(24, 96, 24, 24),
      children: [
        Icon(
          icon,
          size: 56,
          color: colors.onSurfaceVariant.withValues(alpha: 0.5),
        ),
        const SizedBox(height: 12),
        Text(
          message,
          textAlign: TextAlign.center,
          style: TextStyle(color: colors.onSurfaceVariant),
        ),
        if (label != null && onAction != null) ...[
          const SizedBox(height: 16),
          Center(
            child: FilledButton.tonal(onPressed: onAction, child: Text(label)),
          ),
        ],
      ],
    );
  }
}
