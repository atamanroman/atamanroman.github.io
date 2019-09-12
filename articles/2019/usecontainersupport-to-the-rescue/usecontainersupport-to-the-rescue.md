# +UseContainerSupport to the Rescuec - How the JVM Finally Plays Nice with Containers

_It's been a pretty busy time at [adorsys.de](https://adorsys.de), but Joe and I have finally found time to write a follow-up to our outdated-but-still-interesting article [JVM Memory Settings in a Container Environment](https://medium.com/adorsys/jvm-memory-settings-in-a-container-environment-64b0840e1d9e). You might want to read it for a more in-depth view how memory management in the JVM works and what happens - or used to happen - if the JVM runs in a container._

## TL;DR

Java 10 introduced `+UseContainerSupport` (enabled by default) which makes the JVM use sane defaults in a container environment. This feature is backported to Java 8 since [8u191](https://www.oracle.com/technetwork/java/javase/8u191-relnotes-5032181.html#JDK-8146115), potentially allowing a [huge percentage of Java deployments in the wild](https://snyk.io/blog/jvm-ecosystem-report-2018/) to properly configure their memory.

---

## What is +UseContainerSupport?

`-XX:+UseContainerSupport` allows the JVM to read [cgroup limits](https://en.wikipedia.org/wiki/Cgroups) like available CPUs and RAM from the host machine and configure itself accordingly. Doing so allows the JVM to die with an `OutOfMemoryError` instead of the container being killed. The flag is available on Java 8u191+, 10 and newer. **It's enabled by default on Linux machines.**

The old (and somewhat broken) flags `-XX:{Min|Max}RAMFraction` are now deprecated. There is a new flag `-XX:MaxRAMPercentage`, that takes a value between _0.0_ and _100.0_ and defaults to _25.0_. So if there is a _1 GB_ memory limit, the JVM heap is limited to _~250 MB_ by default. While this can certainly be improved - depending on the RAM size and workload - it's a pretty good default [compared to the old behaviour](https://medium.com/adorsys/jvm-memory-settings-in-a-container-environment-64b0840e1d9e). 

**Please note that setting `-Xmx` and `-Xms` disables the automatic heap sizing.**

```
# check if +UseContainerSupport is enabled
$ java -XX:+PrintFlagsFinal -version | grep UseContainerSupport
 bool UseContainerSupport = true {product}
 
# how it works
$ docker run --rm -ti -m10m adorsys/java:8 java -XX:+PrintFlagsFinal -version | grep MaxHeapSize
 uintx MaxHeapSize := 8388608 {product} # ~8M / 80% 
$ docker run --rm -ti -m100m adorsys/java:8 java -XX:+PrintFlagsFinal -version | grep MaxHeapSize
 uintx MaxHeapSize := 50331648 {product} # ~50M / 50%
$ docker run --rm -ti -m200m adorsys/java:8 java -XX:+PrintFlagsFinal -version | grep MaxHeapSize
 uintx MaxHeapSize := 100663296 {product} # ~100M / 50%
$ docker run --rm -ti -m20g adorsys/java:8 java -XX:+PrintFlagsFinal -version | grep MaxHeapSize
 uintx MaxHeapSize := 5001707520 {product} # ~5G / 25%
$ docker run --rm -ti -m20g adorsys/java:8 java -XX:+PrintFlagsFinal -XX:MaxRAMPercentage=90.0 -version | grep MaxHeapSize
 uintx MaxHeapSize := 19327352832 # ~19G / 90%
```

## Our Experience in Prod

We use `-XX:+UseContainerSupport` successfully in production. With reasonable RAM limits (> 1GB) we default to `-XX:MaxRAMPercentage=75.0`. This leaves enough free RAM for other processes like a debug shell and doesn't waste too many resources.