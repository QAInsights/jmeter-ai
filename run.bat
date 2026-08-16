@echo off
setlocal enabledelayedexpansion
call mvn clean package -DskipTests
for /f %%v in ('powershell -NoProfile -Command "([xml](Get-Content pom.xml)).project.version"') do set VERSION=%%v
del C:\Users\Navee\tools\apache-jmeter-5.6.3\lib\ext\jmeter-agent-*.jar 2>nul
copy C:\Users\Navee\gits\jmeter-ai\target\jmeter-agent-!VERSION!.jar C:\Users\Navee\tools\apache-jmeter-5.6.3\lib\ext