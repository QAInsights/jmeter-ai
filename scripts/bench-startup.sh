#!/usr/bin/env bash
set -euo pipefail
# Usage: bench-startup.sh STOCK_JMETER BASELINE_FAT THIN DEPS OUTPUT_DIRECTORY
python3 - "$@" <<'PY'
import json
import pathlib
import shutil
import statistics
import subprocess
import sys
import time

stock, fat, thin, deps, output = map(lambda p: pathlib.Path(p).resolve(), sys.argv[1:])
output.mkdir(parents=True, exist_ok=True)
plan = output / 'empty.jmx'
plan.write_text('''<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
<hashTree><TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Empty" enabled="true">
<elementProp name="TestPlan.user_defined_variables" elementType="Arguments"/>
</TestPlan><hashTree/></hashTree></jmeterTestPlan>''')
results = {}
for label in ('stock', 'fat', 'split'):
    home = output / label
    shutil.copytree(stock, home, dirs_exist_ok=False)
    if label == 'fat':
        shutil.copy2(fat, home / 'lib/ext/jmeter-agent-4.0.0.jar')
    if label == 'split':
        shutil.copy2(thin, home / 'lib/ext' / thin.name)
        shutil.copy2(deps, home / 'lib' / deps.name)
    timings = []
    for run in range(11):
        with (output / f'{label}-{run}.log').open('w') as log:
            start = time.perf_counter()
            subprocess.run([str(home / 'bin/jmeter'), '-n', '-t', str(plan), '-l', '/dev/null'],
                           cwd=home / 'bin', stdout=log, stderr=subprocess.STDOUT, check=True)
            elapsed = time.perf_counter() - start
        if run:
            timings.append(elapsed)
    results[label] = timings
    print(f'{label}: median={statistics.median(timings):.3f}s min={min(timings):.3f}s max={max(timings):.3f}s', flush=True)
(output / 'timings.json').write_text(json.dumps(results, indent=2))
PY
