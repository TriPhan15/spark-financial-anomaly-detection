enum AppMode { demo, full }

class AppConfig {
  static AppMode mode = AppMode.full;

  static const String _apiBase = String.fromEnvironment(
    "API_BASE",
    defaultValue: "https://api.ledgermind.dev",
  );

  static const String _mlBase = String.fromEnvironment(
    "ML_BASE",
    defaultValue: "https://api.ledgermind.dev/ai",
  );

  static String get apiBaseUrl => _apiBase;
  static String get mlApiUrl => _mlBase;
  static const String apiVersion = "/api";

  static const String googleServerClientId =
      "357487248201-up4rikcc0d65r2mbdihs81192obh2jvd.apps.googleusercontent.com";

  static bool get isDemo => mode == AppMode.demo;
  static bool get isFull => mode == AppMode.full;
}
