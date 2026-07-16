#!/bin/bash
set -e

echo "Building production APK..."
./gradlew assembleRelease

echo ""
echo "✓ Production APK built successfully!"
echo "Location: app/build/outputs/apk/release/app-release.apk"
