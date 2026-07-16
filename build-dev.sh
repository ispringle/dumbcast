#!/bin/bash
set -e

echo "Building development APK..."
./gradlew assembleDebug

echo ""
echo "✓ Development APK built successfully!"
echo "Location: app/build/outputs/apk/debug/app-debug.apk"
