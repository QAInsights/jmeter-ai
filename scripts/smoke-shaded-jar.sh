#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JMETER_HOME="${JMETER_HOME:-$HOME/apache-jmeter-5.6.3}"
export JMETER_HOME

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

MAVEN_LOCAL_REPOSITORY="$(
    cd "$ROOT"
    mvn -B -ntp -q help:evaluate -Dexpression=settings.localRepository -DforceStdout
)"
if [ -z "$MAVEN_LOCAL_REPOSITORY" ] || [ ! -d "$MAVEN_LOCAL_REPOSITORY" ]; then
    echo "Could not resolve the configured Maven local repository: $MAVEN_LOCAL_REPOSITORY" >&2
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

java --add-modules jdk.httpserver -Djava.awt.headless=true \
    -Dmaven.repo.local="$MAVEN_LOCAL_REPOSITORY" -cp "$classpath" \
    org.qainsights.jmeter.ai.service.ShadedJarSmoke
