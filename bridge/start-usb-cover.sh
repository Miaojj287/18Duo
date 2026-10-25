#!/usr/bin/env bash
set -euo pipefail
bridge_root=$(cd "$(dirname "$0")/.." && pwd -P)
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or newer}"
bridge_serial=${1:?Pass the authorized device serial}
bridge_adb="$ANDROID_HOME/platform-tools/adb"
# Stop only this helper, using SIGTERM so it releases any owned display request.
bridge_pids=$("$bridge_adb" -s "$bridge_serial" shell ps -A -o PID,ARGS | awk '$2 == "app_process" && $4 == "com.jake.duolauncher.bridge.CoverBridge" {print $1}')
for bridge_pid in $bridge_pids; do
    "$bridge_adb" -s "$bridge_serial" shell kill -TERM "$bridge_pid"
done
bridge_out="$bridge_root/bridge/build"
mkdir -p "$bridge_out/classes" "$bridge_out/dex"
"$JAVA_HOME/bin/javac" -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-36/android.jar" -d "$bridge_out/classes" "$bridge_root/bridge/CoverBridge.java"
"$JAVA_HOME/bin/jar" cf "$bridge_out/classes.jar" -C "$bridge_out/classes" .
"$ANDROID_HOME/build-tools/36.0.0/d8" --lib "$ANDROID_HOME/platforms/android-36/android.jar" --min-api 31 --output "$bridge_out/dex" "$bridge_out/classes.jar"
"$JAVA_HOME/bin/jar" cf "$bridge_out/18Duo-cover-bridge.jar" -C "$bridge_out/dex" classes.dex
"$ANDROID_HOME/platform-tools/adb" -s "$bridge_serial" push "$bridge_out/18Duo-cover-bridge.jar" /data/local/tmp/18Duo-cover-bridge.jar
# Store the pairing token outside the source project; never print it.
bridge_token_store="${DUO18_USB_TOKEN_FILE:-${TMPDIR:-/tmp}/18Duo-core-cover-${bridge_serial}.token}"
if [[ ! -f "$bridge_token_store" ]]; then
    umask 077
    openssl rand -hex 32 > "$bridge_token_store"
fi
"$ANDROID_HOME/platform-tools/adb" -s "$bridge_serial" push "$bridge_token_store" /data/local/tmp/18Duo-cover-token
"$ANDROID_HOME/platform-tools/adb" -s "$bridge_serial" shell 'chmod 600 /data/local/tmp/18Duo-cover-token'
# Provision the private token without displaying it in shell output.
bridge_token=$(cat "$bridge_token_store")
"$ANDROID_HOME/platform-tools/adb" -s "$bridge_serial" shell am start -n com.jake.duolauncher.core/com.jake.duolauncher.MainActivity --es duo_usb_cover_token "$bridge_token" >/dev/null
unset bridge_token
"$ANDROID_HOME/platform-tools/adb" -s "$bridge_serial" shell 'CLASSPATH=/data/local/tmp/18Duo-cover-bridge.jar nohup app_process /system/bin com.jake.duolauncher.bridge.CoverBridge </dev/null >/data/local/tmp/18Duo-cover-bridge.log 2>&1 &'
"$ANDROID_HOME/platform-tools/adb" -s "$bridge_serial" shell 'sleep 1; cat /data/local/tmp/18Duo-cover-bridge.log'
