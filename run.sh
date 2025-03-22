#!/bin/bash

mvn clean package

if [ -f /Users/naveenkumar/Tools/apache-jmeter-5.6.3/lib/ext/jmeter-agent-1.0.6-jar-with-dependencies.jar ]; then
    rm /Users/naveenkumar/Tools/apache-jmeter-5.6.3/lib/ext/jmeter-agent-1.0.6-jar-with-dependencies.jar
fi

cp target/jmeter-agent-1.0.6-jar-with-dependencies.jar /Users/naveenkumar/Tools/apache-jmeter-5.6.3/lib/ext

