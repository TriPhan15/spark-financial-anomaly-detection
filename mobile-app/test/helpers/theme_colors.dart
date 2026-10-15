import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// The colour the [Text] or the [Icon] found by [finder] is painted with: both draw through a RichText.
Color? paintedColor(WidgetTester tester, Finder finder) => tester
    .widget<RichText>(
      find.descendant(of: finder, matching: find.byType(RichText)).first,
    )
    .text
    .style
    ?.color;

/// The colour of the first [Material] inside what [owner] finds: a Scaffold, a dialog, a bottom sheet, a button...
Color? materialColor(WidgetTester tester, Finder owner) => tester
    .widget<Material>(
      find.descendant(of: owner, matching: find.byType(Material)).first,
    )
    .color;

/// The fill of the nearest coloured [Container] around what [finder] finds, or null when there is none.
Color? boxColor(WidgetTester tester, Finder finder) {
  for (final container in tester.widgetList<Container>(
    find.ancestor(of: finder, matching: find.byType(Container)),
  )) {
    final decoration = container.decoration;
    final color = decoration is BoxDecoration
        ? decoration.color
        : container.color;
    if (color != null) return color;
  }
  return null;
}

/// The decoration of the nearest [Container] around what [finder] finds that has a [BoxDecoration].
BoxDecoration boxDecoration(WidgetTester tester, Finder finder) => tester
    .widgetList<Container>(
      find.ancestor(of: finder, matching: find.byType(Container)),
    )
    .map((container) => container.decoration)
    .whereType<BoxDecoration>()
    .first;

/// WCAG contrast ratio of two opaque colours: 4.5 is the minimum for body text, 3 for large text and icons.
double contrastRatio(Color a, Color b) {
  final la = a.computeLuminance();
  final lb = b.computeLuminance();
  return (math.max(la, lb) + 0.05) / (math.min(la, lb) + 0.05);
}

/// [color] as it looks painted over [background], for colours with transparency.
Color over(Color color, Color background) =>
    Color.alphaBlend(color, background);
