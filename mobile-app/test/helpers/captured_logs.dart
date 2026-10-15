import 'package:flutter/foundation.dart';

/// Runs [body] while everything the app writes with debugPrint is collected in the list it is given, so a failure that
/// is logged on purpose neither clutters the test output nor goes unchecked.
///
/// debugPrint is restored before [body] returns: flutter_test checks that at the end of the test body, before any
/// addTearDown callback runs.
Future<void> withCapturedLogs(
  Future<void> Function(List<String> logs) body,
) async {
  final logs = <String>[];
  final original = debugPrint;
  debugPrint = (String? message, {int? wrapWidth}) => logs.add(message ?? '');
  try {
    await body(logs);
  } finally {
    debugPrint = original;
  }
}
