#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
if command -v mvn >/dev/null 2>&1; then
  mvn test
fi
mkdir -p target/test-classes
find src/main/java -name '*.java' ! -name 'OshiProcessProbe.java' ! -name 'OshiHostProbe.java' > target/test-sources.txt
echo src/test/standalone/IntegrationSelfTest.java >> target/test-sources.txt
echo src/test/standalone/SnapshotConcurrencySelfTest.java >> target/test-sources.txt
if javac --help 2>&1 | grep -q -- '--release'; then
  javac --release 8 -d target/test-classes @target/test-sources.txt
else
  javac -source 8 -target 8 -d target/test-classes @target/test-sources.txt
fi
cp -R src/main/resources/. target/test-classes/
java -cp target/test-classes IntegrationSelfTest
exec java -cp target/test-classes SnapshotConcurrencySelfTest
