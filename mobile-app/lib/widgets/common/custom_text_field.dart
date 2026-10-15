import 'package:flutter/material.dart';

class CustomTextField extends StatelessWidget {
  final TextEditingController controller;
  final String label;
  final bool isPassword;
  final String obscuringCharacter;
  final ValueChanged<String>? onChanged;
  final TextInputType? keyboardType;
  final Widget? suffixIcon;
  final bool obscureText;

  const CustomTextField({
    super.key,
    required this.controller,
    required this.label,
    this.isPassword = false,
    this.obscuringCharacter = '•',
    this.onChanged,
    this.keyboardType,
    this.suffixIcon,
    this.obscureText = false,
  }) : assert(obscuringCharacter.length == 1);

  /// The text, the fill, the cursor and the suffix icon come from the theme; the label keeps the secondary text colour
  /// also when focused, and the field keeps its own rounder corners.
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final border = OutlineInputBorder(
      borderRadius: BorderRadius.circular(16),
      borderSide: BorderSide(color: colors.outlineVariant),
    );
    return TextField(
      controller: controller,
      keyboardType: keyboardType,
      obscureText: isPassword || obscureText,
      obscuringCharacter: obscuringCharacter,
      enableSuggestions: !isPassword,
      autocorrect: !isPassword,
      onChanged: onChanged,
      decoration: InputDecoration(
        labelText: label,
        labelStyle: TextStyle(color: colors.onSurfaceVariant),
        filled: true,
        border: border,
        enabledBorder: border,
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(color: colors.primary),
        ),
        suffixIcon: suffixIcon,
      ),
    );
  }
}
