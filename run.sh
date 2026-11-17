#!/usr/bin/env bash
# Build Feather Wand and install the split jars into a local JMeter.
# Usage: JMETER_HOME=~/tools/apache-jmeter-5.6.3 ./run.sh
set -euo pipefail
cd "$(dirname "$0")"

: "${JMETER_HOME:?Set JMETER_HOME to your JMeter install, e.g. JMETER_HOME=~/tools/apache-jmeter-5.6.3 ./run.sh}"
EXT_DIR="$JMETER_HOME/lib/ext"
if [ ! -d "$EXT_DIR" ]; then
    echo "No lib/ext directory under JMETER_HOME ($JMETER_HOME)" >&2
    exit 1
fi

mvn -q clean package -DskipTests

VERSION=$(grep -m1 -A2 '<artifactId>jmeter-agent</artifactId>' pom.xml | sed -n 's:.*<version>\(.*\)</version>.*:\1:p')
JAR="target/jmeter-agent-$VERSION.jar"
DEPS="target/zzz-jmeter-agent-deps-$VERSION.jar"
test -f "$JAR" && test -f "$DEPS"

rm -f "$EXT_DIR"/jmeter-agent-*.jar
rm -f "$JMETER_HOME/lib"/zzz-jmeter-agent-*-deps.jar "$JMETER_HOME/lib"/zzz-jmeter-agent-deps-*.jar
cp "$JAR" "$EXT_DIR/"
cp "$DEPS" "$JMETER_HOME/lib/"
echo "Installed $(basename "$JAR") into $EXT_DIR"
echo "Installed $(basename "$DEPS") into $JMETER_HOME/lib"
