#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/build/core-tests
src=app/src/main/java/ru/limar/pingmonitor
javac -encoding UTF-8 --release 17 --add-modules jdk.httpserver -d app/build/core-tests \
  "$src/Target.java" "$src/Probe.java" "$src/Health.java" "$src/Csv.java" tests/CoreTests.java
java --add-modules jdk.httpserver -cp app/build/core-tests ru.limar.pingmonitor.CoreTests
