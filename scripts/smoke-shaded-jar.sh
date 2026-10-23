#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JMETER_HOME="${JMETER_HOME:-$HOME/apache-jmeter-5.6.3}"

candidate_jars=()
for candidate in "$ROOT"/target/jmeter-agent-*.jar; do
    [ -f "$candidate" ] || continue
    case "$(basename "$candidate")" in
        *-sources.jar|*-javadoc.jar|original-*|*-original-*.jar) continue ;;
    esac
    candidate_jars[${#candidate_jars[@]}]="$candidate"
done

if (( ${#candidate_jars[@]} == 0 )); then
    echo "No shaded JAR found in $ROOT/target; expected one jmeter-agent-*.jar excluding sources, javadoc, and original artifacts." >&2
    exit 1
fi
if (( ${#candidate_jars[@]} > 1 )); then
    printf "Expected exactly one shaded JAR in %s/target, found %d:\n" "$ROOT" "${#candidate_jars[@]}" >&2
    printf '  %s\n' "${candidate_jars[@]}" >&2
    exit 1
fi
JAR="${candidate_jars[0]}"

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
