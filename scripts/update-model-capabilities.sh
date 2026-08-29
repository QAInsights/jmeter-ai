#!/usr/bin/env bash
#
# Vendors the models.dev model capability data into the repository.
# The generated file is committed and reviewed like a dependency update;
# nothing is fetched at application runtime.

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
url="https://models.dev/api.json"
out_file="$repo_root/src/main/resources/org/qainsights/jmeter/ai/reasoning/model-capabilities.json"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/modelsdev.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

input_file="$tmp_dir/api.json"
classpath_file="$tmp_dir/classpath.txt"
classes_dir="$tmp_dir/classes"
mkdir -p "$classes_dir" "$(dirname -- "$out_file")"

echo "Downloading models.dev model data ..."
curl --fail --location --silent --show-error "$url" --output "$input_file"

echo "Resolving Java dependencies from pom.xml ..."
(
  cd "$repo_root"
  mvn --batch-mode --no-transfer-progress dependency:build-classpath \
    "-Dmdep.outputFile=$classpath_file"
)

classpath="$(<"$classpath_file")"
if [[ -z "$classpath" ]]; then
  echo "Maven produced an empty Java classpath" >&2
  exit 1
fi

echo "Trimming to supported providers ..."
javac -cp "$classpath" -d "$classes_dir" "$script_dir/TrimModelCapabilities.java"
java -cp "$classpath:$classes_dir" TrimModelCapabilities "$input_file" "$out_file"

echo "Vendored capabilities -> $out_file"
