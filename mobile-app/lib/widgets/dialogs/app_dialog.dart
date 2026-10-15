import 'package:flutter/material.dart';

import 'package:finance_ai_app/core/extensions/build_context_extension.dart';

/// What a dialog tells the user; its accent colour is read from the theme when the dialog is built.
enum _Tone { success, error, warning, info }

class AppDialog {
  static Future<void> success(
    BuildContext context, {
    required String title,
    String? message,
    String buttonText = 'OK',
  }) => _show(
    context,
    icon: Icons.check_circle_rounded,
    tone: _Tone.success,
    title: title,
    message: message,
    confirmText: buttonText,
  ).then((_) {});

  static Future<void> error(
    BuildContext context, {
    required String title,
    String? message,
    String buttonText = 'Đã hiểu',
  }) => _show(
    context,
    icon: Icons.error_rounded,
    tone: _Tone.error,
    title: title,
    message: message,
    confirmText: buttonText,
  ).then((_) {});

  static Future<void> warning(
    BuildContext context, {
    required String title,
    String? message,
    String buttonText = 'Đã hiểu',
  }) => _show(
    context,
    icon: Icons.warning_amber_rounded,
    tone: _Tone.warning,
    title: title,
    message: message,
    confirmText: buttonText,
  ).then((_) {});

  static Future<void> info(
    BuildContext context, {
    required String title,
    String? message,
    String buttonText = 'OK',
  }) => _show(
    context,
    icon: Icons.info_rounded,
    tone: _Tone.info,
    title: title,
    message: message,
    confirmText: buttonText,
  ).then((_) {});

  static Future<bool> confirm(
    BuildContext context, {
    required String title,
    String? message,
    String confirmText = 'Đồng ý',
    String cancelText = 'Hủy',
    bool danger = false,
  }) async {
    final r = await _show(
      context,
      icon: danger ? Icons.warning_amber_rounded : Icons.help_outline_rounded,
      tone: danger ? _Tone.error : _Tone.info,
      title: title,
      message: message,
      confirmText: confirmText,
      cancelText: cancelText,
    );
    return r == true;
  }

  static Color _accentOf(_Tone tone, ThemeData theme) => switch (tone) {
    _Tone.success => theme.semanticColors.successColor,
    _Tone.error => theme.colorScheme.error,
    _Tone.warning => theme.semanticColors.warningColor,
    _Tone.info => theme.colorScheme.primary,
  };

  static Future<bool?> _show(
    BuildContext context, {
    required IconData icon,
    required _Tone tone,
    required String title,
    String? message,
    required String confirmText,
    String? cancelText,
  }) {
    return showGeneralDialog<bool>(
      context: context,
      barrierDismissible: cancelText == null,
      barrierLabel: 'dialog',
      barrierColor: Colors.black54,
      transitionDuration: const Duration(milliseconds: 240),
      transitionBuilder: (ctx, anim, _, child) {
        final curved = CurvedAnimation(parent: anim, curve: Curves.easeOutBack);
        return Opacity(
          opacity: anim.value.clamp(0.0, 1.0),
          child: Transform.scale(
            scale: 0.85 + 0.15 * curved.value,
            child: child,
          ),
        );
      },
      pageBuilder: (ctx, _, _) {
        final theme = Theme.of(ctx);
        final colors = theme.colorScheme;
        final accent = _accentOf(tone, theme);
        // The dialog takes the surface colour of the theme; the title keeps the text colour it gives.
        return Dialog(
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(24),
          ),
          insetPadding: const EdgeInsets.symmetric(horizontal: 32),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(24, 28, 24, 20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 64,
                  height: 64,
                  decoration: BoxDecoration(
                    color: accent.withValues(alpha: 0.12),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(icon, color: accent, size: 34),
                ),
                const SizedBox(height: 18),
                Text(
                  title,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                if (message != null) ...[
                  const SizedBox(height: 8),
                  Text(
                    message,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: colors.onSurfaceVariant,
                      fontSize: 14,
                      height: 1.4,
                    ),
                  ),
                ],
                const SizedBox(height: 24),
                Row(
                  children: [
                    if (cancelText != null) ...[
                      Expanded(
                        child: OutlinedButton(
                          onPressed: () => Navigator.pop(ctx, false),
                          style: OutlinedButton.styleFrom(
                            foregroundColor: colors.onSurfaceVariant,
                            side: BorderSide(color: colors.outlineVariant),
                            padding: const EdgeInsets.symmetric(vertical: 14),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(14),
                            ),
                          ),
                          child: Text(cancelText),
                        ),
                      ),
                      const SizedBox(width: 12),
                    ],
                    Expanded(
                      child: ElevatedButton(
                        onPressed: () => Navigator.pop(ctx, true),
                        // The label takes the dialog's surface colour: white in light mode as before, dark on the
                        // light accents of the dark theme, which keeps them at 4.5:1 or more against that surface.
                        style: ElevatedButton.styleFrom(
                          backgroundColor: accent,
                          foregroundColor: colors.surface,
                          elevation: 0,
                          padding: const EdgeInsets.symmetric(vertical: 14),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(14),
                          ),
                        ),
                        child: Text(confirmText),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
