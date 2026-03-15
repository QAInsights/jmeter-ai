@echo off
call mvn clean package -DskipTests
if exist C:\Tools\apache-jmeter-5.6.3\lib\ext\jmeter-agent-1.0.11.jar (
    del C:\Tools\apache-jmeter-5.6.3\lib\ext\jmeter-agent-1.0.11.jar
)
copy C:\Users\Navee\gits\jmeter-ai\target\jmeter-agent-1.0.11.jar C:\Users\Navee\tools\apache-jmeter-5.6.3\lib\ext