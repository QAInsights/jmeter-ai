#!/usr/bin/env bash
# Build Feather Wand and install the plugin jar into a local JMeter.
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

rm -f "$EXT_DIR"/jmeter-agent-*.jar
cp "$JAR" "$EXT_DIR/"
echo "Installed $(basename "$JAR") into $EXT_DIR"
