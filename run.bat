@echo off
rem Build Feather Wand and install the plugin jar into a local JMeter.
rem Usage: set JMETER_HOME=C:\tools\apache-jmeter-5.6.3 then run.bat
setlocal enabledelayedexpansion
cd /d "%~dp0"

if "%JMETER_HOME%"=="" (
    echo Set JMETER_HOME to your JMeter install, e.g. set JMETER_HOME=C:\tools\apache-jmeter-5.6.3
    exit /b 1
)
if not exist "%JMETER_HOME%\lib\ext\" (
    echo No lib\ext directory under JMETER_HOME ^(%JMETER_HOME%^)
    exit /b 1
)

call mvn -q clean package -DskipTests || exit /b 1
for /f %%v in ('powershell -NoProfile -Command "([xml](Get-Content pom.xml)).project.version"') do set VERSION=%%v

del "%JMETER_HOME%\lib\ext\jmeter-agent-*.jar" 2>nul
copy /y "target\jmeter-agent-!VERSION!.jar" "%JMETER_HOME%\lib\ext\" >nul || exit /b 1
echo Installed jmeter-agent-!VERSION!.jar into %JMETER_HOME%\lib\ext
