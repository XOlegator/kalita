#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

log=$(mktemp)
trap 'rm -f "$log"' EXIT
task=:modules:app-android:connectedDebugAndroidTest

if ./gradlew --console=plain -Pfdroid "$task" 2>&1 | tee "$log"; then
    :
else
    status=$?
    if ! grep -Fq 'Received status code 429 from server' "$log"; then
        exit "$status"
    fi
    echo "Gradle dependency download was rate-limited (HTTP 429). Retrying in 30 seconds."
    sleep 30
    ./gradlew --console=plain -Pfdroid "$task"
fi

mkdir -p scenario82-output
adb pull /data/local/tmp/scenario82.mdb scenario82-output/
adb pull /data/local/tmp/scenario82.expected.txt scenario82-output/
