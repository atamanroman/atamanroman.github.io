#!/usr/bin/env bash

set -ex

JAVA_OPTS="-XX:+PrintFlagsFinal -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -XX:MaxRAMFraction=1"

if [ $# -eq 0 ] ; then
  JAVA_OPTS=$@
fi

echo "Start with $JAVA_OPTS"

docker run -d --name loadtest --rm -v $PWD/target/loadtest-1.0-SNAPSHOT.jar:/tmp/loadtest.jar -m 512m --memory-swap 512m -w /tmp -p 8080:8080 openjdk:8-jre java -jar $JAVA_OPTS loadtest.jar

sleep 5 # wait until server is up
if ab -n 1000 -c 100 localhost:8080/; then

  echo "all good, deployment did not die"
  docker stop loadtest

else

  echo "container died :("

fi

