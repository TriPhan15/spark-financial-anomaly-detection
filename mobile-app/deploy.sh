#!/usr/bin/env bash
# Rebuild + reinstall app on connected phone.
# IP = PC LAN IP (same WiFi as phone). Override: PC_IP=x.x.x.x ./deploy.sh
set -e

PC_IP="${PC_IP:-172.17.96.220}"
PKG="com.example.finance_ai_app"

cd "$(dirname "$0")"

echo ">> Build APK (API/ML -> $PC_IP)"
flutter build apk --release \
  --dart-define=API_BASE=http://${PC_IP}:8080 \
  --dart-define=ML_BASE=http://${PC_IP}:8000

echo ">> Install on phone"
adb install -r build/app/outputs/flutter-apk/app-release.apk \
  || { echo "Reinstall failed -> uninstall + retry"; adb uninstall "$PKG"; \
       adb install build/app/outputs/flutter-apk/app-release.apk; }

echo ">> Done"
