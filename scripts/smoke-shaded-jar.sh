#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JMETER_HOME="${JMETER_HOME:-$HOME/apache-jmeter-5.6.3}"
JAR="$ROOT/target/jmeter-agent-3.9.0.jar"

if [ ! -f "$JAR" ]; then
    echo "Shaded JAR not found: $JAR" >&2
    exit 1
fi
if [ ! -d "$JMETER_HOME/lib" ] || [ ! -d "$JMETER_HOME/lib/ext" ]; then
    echo "Set JMETER_HOME to a JMeter 5.6.3 installation" >&2
    exit 1
fi

classpath="$ROOT/target/test-classes:$JAR"
while IFS= read -r -d '' dependency; do
    case "$(basename "$dependency")" in
        jmeter-agent*.jar) continue ;;
    esac
    classpath="$classpath:$dependency"
done < <(find "$JMETER_HOME/lib" "$JMETER_HOME/lib/ext" -type f -name '*.jar' -print0)

java --add-modules jdk.httpserver -Djava.awt.headless=true -cp "$classpath" \
    org.qainsights.jmeter.ai.service.ShadedJarSmoke
