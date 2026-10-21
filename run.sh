#!/usr/bin/env sh
# One command: Maven build with OSHI when available; otherwise no-dependency Linux fallback.
set -eu
cd "$(dirname "$0")"
if command -v mvn >/dev/null 2>&1; then
  mvn -q -DskipTests package
  exec java -jar target/quant-performance-lab-0.1.0.jar "$@"
fi
if ! command -v javac >/dev/null 2>&1; then
  echo 'Requires a Java 8+ JDK (javac) or Maven and a built fat JAR.' >&2; exit 1
fi
if [ -f target/quant-performance-lab-0.1.0.jar ]; then
  exec java -jar target/quant-performance-lab-0.1.0.jar "$@"
fi
# No build tools beyond JDK: compile core and Linux fallback (OSHI adapter requires Maven).
mkdir -p target/fallback-classes
find src/main/java -name '*.java' ! -name 'OshiProcessProbe.java' ! -name 'OshiHostProbe.java' > target/fallback-sources.txt
if javac --help 2>&1 | grep -q -- '--release'; then
  javac --release 8 -d target/fallback-classes @target/fallback-sources.txt
else
  javac -source 8 -target 8 -d target/fallback-classes @target/fallback-sources.txt
fi
cp -R src/main/resources/. target/fallback-classes/
exec java -cp target/fallback-classes io.quantlab.Main "$@"
