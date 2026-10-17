#!/usr/bin/env bash
# Builds the app and installs it on the phone connected through adb (USB cable or wireless debugging).
# Requirements: JDK 17+ (JAVA_HOME or java on the PATH) and the Android SDK (ANDROID_HOME or ~/Android/Sdk).
set -euo pipefail
cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
if [ -z "${JAVA_HOME:-}" ] && ! command -v java > /dev/null && [ -d "$HOME/Android/jdk-21" ]; then
    export JAVA_HOME="$HOME/Android/jdk-21"
fi
ADB="$ANDROID_HOME/platform-tools/adb"

if [ "$("$ADB" get-state 2> /dev/null)" != "device" ]; then
    echo "No phone ready. Turn on USB debugging, plug in the cable and tap \"Allow\" on the phone."
    "$ADB" devices -l
    exit 1
fi

./gradlew --console=plain assembleRelease
"$ADB" install -r app/build/outputs/apk/release/app-release.apk
"$ADB" shell am start -n com.implacavel.alarme/.MainActivity > /dev/null
echo "Done! Nightmare Alarm is open on the phone."
