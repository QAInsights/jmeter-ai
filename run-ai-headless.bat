@echo off
REM Run the JMeter AI headless runner from a CI pipeline (Windows).
REM
REM Usage:
REM   run-ai-headless.bat --jmx test.jmx --prompt "Lint this plan" --fail-on-error
REM
REM Requires KIRO_API_KEY in the environment for Kiro headless runs.
setlocal

set "SCRIPT_DIR=%~dp0"
set "JAR="

for %%F in ("%SCRIPT_DIR%target\jmeter-agent-*.jar") do set "JAR=%%F"
if not defined JAR if defined JMETER_HOME for %%F in ("%JMETER_HOME%\lib\ext\jmeter-agent*.jar") do set "JAR=%%F"

if not defined JAR (
  echo Could not find jmeter-agent jar. Build with "mvn package" or set JMETER_HOME. 1>&2
  exit /b 3
)

set "CP=%JAR%"
if defined JMETER_HOME if exist "%JMETER_HOME%\lib" set "CP=%JAR%;%JMETER_HOME%\lib\*;%JMETER_HOME%\lib\ext\*"

java -cp "%CP%" org.qainsights.jmeter.ai.headless.HeadlessAiRunner %*
