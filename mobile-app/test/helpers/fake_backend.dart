import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

/// Stands in for core-backend: it records every request the app makes and answers with the JSON of the /api/budgets
/// contract (ApiResponse with code, message and data).
class FakeBackend implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  final Map<String, FutureOr<ResponseBody> Function(RequestOptions)> _routes =
      {};

  void on(
    String method,
    String path,
    FutureOr<ResponseBody> Function(RequestOptions) handler,
  ) {
    _routes['$method $path'] = handler;
  }

  void reset() {
    requests.clear();
    _routes.clear();
  }

  List<String> get calls =>
      requests.map((r) => '${r.method} ${r.path}').toList();

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    final handler = _routes['${options.method} ${options.path}'];
    if (handler == null) {
      return jsonResponse(404, {
        'code': 404,
        'message': 'No such route in the fake backend',
      });
    }
    return handler(options);
  }

  @override
  void close({bool force = false}) {}
}

ResponseBody jsonResponse(int status, Object body) => ResponseBody.fromString(
  jsonEncode(body),
  status,
  headers: {
    Headers.contentTypeHeader: [Headers.jsonContentType],
  },
);

ResponseBody okResponse(
  Object? data, {
  int status = 200,
  String message = 'OK',
}) => jsonResponse(status, {'code': status, 'message': message, 'data': data});
