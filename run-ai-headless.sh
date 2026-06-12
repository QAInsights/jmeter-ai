#!/usr/bin/env bash
# Run the JMeter AI headless runner from a CI pipeline.
#
# Usage:
#   ./run-ai-headless.sh --jmx test.jmx --prompt "Lint this plan" --fail-on-error
#
# Requires KIRO_API_KEY in the environment for Kiro headless runs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Locate the plugin jar: prefer a built target/ artifact, else JMeter's lib/ext.
JAR=""
for candidate in \
    "$SCRIPT_DIR"/target/jmeter-agent-*.jar \
    "${JMETER_HOME:-}"/lib/ext/jmeter-agent*.jar \
    "$SCRIPT_DIR"/lib/ext/jmeter-agent*.jar; do
  if [ -f "$candidate" ]; then JAR="$candidate"; break; fi
done

if [ -z "$JAR" ]; then
  echo "Could not find jmeter-agent jar. Build with 'mvn package' or set JMETER_HOME." >&2
  exit 3
fi

# Include JMeter's libs on the classpath if available (for JMeterUtils etc.).
CP="$JAR"
if [ -n "${JMETER_HOME:-}" ] && [ -d "$JMETER_HOME/lib" ]; then
  CP="$JAR:$JMETER_HOME/lib/*:$JMETER_HOME/lib/ext/*"
fi

exec java -cp "$CP" org.qainsights.jmeter.ai.headless.HeadlessAiRunner "$@"
