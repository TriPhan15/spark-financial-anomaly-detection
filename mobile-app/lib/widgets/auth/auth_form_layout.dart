import 'package:flutter/material.dart';

/// The page of a form of the sign-in flow: an app bar with [title], then [heading] and [description] above [child], all
/// scrolling together so that nothing is cut off on a small phone or hidden behind the keyboard. Colours and text styles
/// are those of the register screen, taken from the theme.
class AuthFormLayout extends StatelessWidget {
  const AuthFormLayout({
    super.key,
    required this.title,
    required this.heading,
    required this.description,
    required this.child,
  });

  final String title;
  final String heading;
  final String description;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    // The Scaffold and its app bar take the page colour of the theme; the heading keeps the text colour it gives.
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 8),
          keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 8),
              Text(
                heading,
                style: const TextStyle(
                  fontSize: 26,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                description,
                style: TextStyle(color: colors.onSurfaceVariant, fontSize: 14),
              ),
              const SizedBox(height: 28),
              child,
              const SizedBox(height: 16),
            ],
          ),
        ),
      ),
    );
  }
}

/// The label above a field, as on the login and register screens.
class AuthFieldLabel extends StatelessWidget {
  const AuthFieldLabel(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8, left: 2),
      child: Align(
        alignment: Alignment.centerLeft,
        child: Text(
          text,
          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
        ),
      ),
    );
  }
}
