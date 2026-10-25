#!/usr/bin/env bash
set -euo pipefail
bridge_root=$(cd "$(dirname "$0")/.." && pwd -P)
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or newer}"
bridge_classes="$bridge_root/bridge/build/host-test"
mkdir -p "$bridge_classes"
"$JAVA_HOME/bin/javac" -cp "$ANDROID_HOME/platforms/android-36/android.jar" -d "$bridge_classes" \
    "$bridge_root/bridge/CoverBridge.java" "$bridge_root/bridge/CoverBridgeTest.java"
"$JAVA_HOME/bin/java" -cp "$bridge_classes:$ANDROID_HOME/platforms/android-36/android.jar" \
    com.jake.duolauncher.bridge.CoverBridgeTest
