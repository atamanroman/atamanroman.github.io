---
title: JVM Memory Settings in a Container Environment - The Bare Minimum You Should Know Before Going Live
description:
date: 2018-11-10
category: [development]
tags: [jvm, docker]
---

## UPDATE: Things Have Changed for Java 8u191+ and 10/11/12+

A lot has changed since Java 8u191 and things should work out of the box. So this article is partly out-of-date. We're keeping it online as a reference but please see our follow-up article [_+UseContainerSupport to the Rescue_]({{< ref "/blog/usecontainersupport-to-the-rescue.md" >}}) for more details.

## About us

[Joe](https://github.com/jkroepke) and [I](https://twitter.com/atamanroman) both do a lot of work with Java on OpenShift/Kubernetes. Joe has a very strong operations background and I feel more at home with software development — yet we often do similar stuff. We also like to ~~argue~~ work together and this article is the result of one particularly interesting discussion about the JVM setting `+UseCGroupMemoryLimit`. We stitched this article together during a few Fridays at the office at [adorsys](https://adorsys.de).

---

## TL;DR

Java memory management and configuration is still complex. Although the JVM can read cgroup memory limits and adapt memory usage accordingly since Java 9/8u131, it's not a golden bullet. You need to know what `-XX:+UseCGroupMemoryLimitForHeap` does and you need to fine tune some parameters for every deployment. Otherwise you risk wasting resources and money or getting your containers killed at the worst time possible. `-XX:MaxRAMFraction=1` is especially dangerous. Java 10+ brings a lot of improvements but still needs manual configuration. To be safe, load test your stuff.

---

## Java Heap Sizing Basics

Per default the JVM automatically configures heap size according to the spec of the machine it is running on. On my brand new MacBook Pro 2018 this yields the following heap size:

```shell
$ java -XX:+PrintFlagsFinal -version | grep -Ei "maxheapsize|maxram"
    uintx DefaultMaxRAMFraction   = 4             {product}
    uintx MaxHeapSize             := 8589934592   {product}
    uint64_t MaxRAM               = 137438953472  {pd product}
    uintx MaxRAMFraction          = 4             {product}
```

As you can see, the JVM defaults to 8.0 GB max heap `(8589934592 / 1024^3)` and 0.5 GB initial heap on my machine. The formula behind this is straight forward. Using the JVM configuration parameter names, we end up with: `MaxHeapSize = MaxRAM * 1 / MaxRAMFraction` where MaxRAM is the available RAM[^maxram] and MaxRAMFraction is 4[^small_ram] by default. That means the **JVM allocates up to 25% of your RAM per JVM** running on your machine.

It's important to note that the JVM uses more memory than just heap. We can calculate the total memory usage roughly with `heap + stack per thread (XSS) * threads + constant overhead`.
The default for XSS depends on the OS and JVM and is somewhere between 256 KB and 1 MB[^xss_sizing]. That means: every thread allocates at least 256 KB additional memory.
The constant overhead is all memory allocated by the JVM which is not heap or stack. This value depends on a lot of factors. See [^jvm_overhead] and `-XX:NativeMemoryTracking`[^nat_mem_track] for more details.

You can change how the JVM does its memory management. The most common thing is to set *MaxHeap* (`-Xmx`) to a fixed, more or less carefully guessed value.

```shell
$ java -XX:+PrintFlagsFinal -Xmx1g -version | grep -Ei "maxheapsize|maxram"

    uintx DefaultMaxRAMFraction   = 4            {product}
    uintx MaxHeapSize             := 1073741824  {product}
    uint64_t MaxRAM               = 137438953472 {pd product}
    uintx MaxRAMFraction          = 4            {product}
```

There are other ways to control the heap, too. We can adjust `MaxRAM`, effectively simulating a smaller machine.

```shell
 $ java -XX:+PrintFlagsFinal -XX:MaxRAM=1g -version | grep -Ei "maxheapsize|maxram"
    uintx DefaultMaxRAMFraction   = 4            {product}
    uintx MaxHeapSize             := 268435456   {product}
    uint64_t MaxRAM               := 1073741824  {pd product}
    uintx MaxRAMFraction          = 4            {product}
```

Now the JVM is back in charge calculating the heap size, we just fine tune the parameters. In this case we end up with 256 MB max heap. That's fine for a desktop, but a bit conservative for a dedicated host. If we spend good money on a VPS with 1 GB RAM, we'd like the JVM to make better use of the available resources. Here `-XX:MaxRAMFraction` comes into play. This parameter controls how much of the total RAM is up for grabs. `1/MaxRAMFraction` yields the percentage of RAM we can use. Since it only allows integer values > 0, there are only a few sensible configurations.

    +----------------+-------------------+
    | MaxRAMFraction | % of RAM for heap |
    |----------------+-------------------|
    |              1 |              100% |
    |              2 |               50% |
    |              3 |               33% |
    |              4 |               25% |
    +----------------+-------------------+

So for our dedicated 1 GB server it's enough to set `-XX:MaxRAMFraction=2` and we end up 512 MB max RAM.

```shell
# -XX:MaxRAM is only set for the sake of this example to simulate a smaller physical machine
$ java -XX:+PrintFlagsFinal -XX:MaxRAM=1g -XX:MaxRAMFraction=2 -version | grep -Ei "maxheapsize|maxram"
    uintx DefaultMaxRAMFraction   = 4            {product}
    uintx MaxHeapSize             := 536870912   {product}
    uint64_t MaxRAM               := 1073741824  {pd product}
    uintx MaxRAMFraction          := 2           {product}
```

That looks pretty good for prod!

## Java Heap Sizing in Containers

There are a lot of things you need to know to properly set up a JVM in a container. For instance, applications running in a Docker container always see the full resources available. It's not very intuitive, but cgroup[^cgroup] limits are not visible for the contained process. See for yourself[^docker_vm]:

```shell
$ docker run --rm alpine free -m
             total     used     free   shared  buffers   cached
Mem:          1998     1565      432        0        8     1244
```

```shell
$ docker run --rm -m 256m alpine free -m
             total     used     free   shared  buffers   cached
Mem:          1998     1552      445        1        8     1244
```

That causes problems with the default JVM sizing. Because there are *a lot* of available resources visible on your expensive Kubernetes node it tries to hog a good amount of it. And this egoism makes sense when the JVM can own a good chunk of the underlying hardware (e.g. a dev workstation or a single purpose VM).

But it makes the JVM behave quite badly on container platforms per default. If the resources are shared between unrelated, more or less equally important processes, the optimistic resource allocation can cause problems. Depending on your current setup, one of two things can happen: a) the JVM is killed as soon as it tries to allocate more memory than it is allowed according to the quotas set in your deployment config or b) the JVM eats your precious resources for breakfast if there are no quotas.

So what's the fix? We *could* configure the JVM manually by setting `-Xmx` or `-XX:MaxRAM` accordingly. Or make the cgroup memory limits visible to it. And that's exactly what has already been done. The first cgroup related patches landed with Java 9 and were backported to Java 8u131[^8u131_release] in April 2017[^8u131_release_notes]. Let's have a closer look.

To make the JVM play well with cgroup memory limits a new option `-XX:+UseCGroupMemoryLimitForHeap` was introduced. It sounds fancy but is pretty simple once you know the basics. It allows setting the heap to the cgroup memory limit. The JVM reads from `/sys/fs/cgroup/memory/memory.limit_in_bytes` and uses that value instead of `-XX:MaxRAM`.

```shell
$ docker run --rm -m 1g openjdk:8-jdk cat /sys/fs/cgroup/memory/memory.limit_in_bytes
1073741824
```

```shell
$ docker run --rm -m 1g openjdk:8-jdk sh -c "java -XX:+PrintFlagsFinal -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -version | grep -Ei 'maxheapsize|maxram'"
    uintx DefaultMaxRAMFraction   = 4             {product}
    uintx MaxHeapSize            := 268435456     {product}     # = 1073741824 / 4
    uint64_t MaxRAM               = 137438953472  {pd product}
    uintx MaxRAMFraction          = 4             {product}
```

And that's it. 1 GB memory quota yields 256 MB heap. Exactly the same as in the non-docker examples. Note that `-XX:+UseCGroupMemoryLimitForHeap` requires `-XX:+UnlockExperimentalVMOptions` in order to work. And there's a good reason to it.

So the JVM is now aware of cgroup memory limits - you just need to enable that feature. Are we done here? We thought so at first. If you searched for *java container heap configuration prod* or something like that, almost every blog post was just advising you to set that flag and have a nice day. And we followed that advice - until we encountered some obviously broken behaviour. Containers limited to very small amounts of memory would almost instantly get *OOMKilled*. Which means the container tried to allocate more than 128 MB.
With everything we knew back then this should not happen. I mean the JVM *knew* there was only so much memory available. If anything, I'd have expected an `OutOfMemoryError` if the heap was really too tight.
On the other hand, containers with larger memory limits were very inefficient. Using less than half of the 8 GB reserved memory per deployment wastes a lot of resources.

As it turns out using the cgroup memory limit instead of `-XX:MaxRAM` is not enough. Depending on the actual limit you can run into efficiency or stability issues. That's where people start tweaking with `-XX:MaxRAMFraction=1` so that the JVM can use all of the RAM for heap. But some basic load testing[^load_tests] showed that's too much. As stated earlier, the JVM needs some memory per thread for its stack and some constant amount on top of it. Also there is often other stuff in a container which could possibly allocate additional memory (like SSHd, monitoring processes, the shell which spawned your process, ...). And, last but not least, you *might* want to have enough free memory to be actually able to `docker exec` into your container to trigger a heap dump or attach a debugger. So you risk getting your container killed under load.

Getting *OOMKilled* is not a good thing. Your app can't react to in any way - it just gets killed. In my opinion, this should never happen. I always want to get an `OutOfMemoryError` when heap runs out so I can get a heap dump and analyse it. *OOMKilled* should only happen if there is something *really broken* like a memory leak in the JVM. That is necessary to protect other deployments on the same node.
If one instance of your service gets killed under heavy load it's more likely that other instances go down with them as well because the load gets rebalanced. This can snowball and kill your whole system, which otherwise might have worked pretty OK if the resources were set and limited properly. The worst thing is that this type of bug only occurs if your system is under stress. Not a good time to fail.

So `-XX:MaxRAMFraction=1` should be avoided in any case. That leaves us with 50% or less memory utilisation. I'd say, that is not acceptable for most configurations. After some testing, we found out that most of our services required roughly 250 MB additional free RAM to be really safe. So the bigger the memory gets, the less useful becomes `-XX:+UseCGroupMemoryLimitForHeap`.

## Better Ways to Configure Heap

There are a few approaches to fix this. You could give up and just set `-Xmx`. Or set `-XX:MaXRAM` to a percentage of the real quota and use `-XX:MaxRAMFraction=1`. The `ENTRYPOINT` would be a good spot for that.

```sh
# set -XX:MaxRAM to 70% of the cgroup limit
docker run --rm -m 1g openjdk:8-jdk sh -c 'exec java -XX:MaxRAM=$(( $(cat /sys/fs/cgroup/memory/memory.limit_in_bytes) * 70 / 100 )) -XX:+PrintFlagsFinal -version'
```

The most elegant solution is to upgrade to Java 10+. Java 10 deprecates `-XX:+UseCGroupMemoryLimitForHeap`[^java11_removed] and introduces `-XX:+UseContainerSupport`[^usecontainersupport], which supersedes it. It also introduces `-XX:MaxRAMPercentage`[^java10_maxramchanges] which takes a value between 0 and 100. This allows fine grained control of the amount of RAM the JVM is allowed to allocate.
Since `+UseContainerSupport` is enabled by default, everything should work out of the box. You may want to fine tune  `-XX:MaxRAMPercentage` to be both _efficient and safe_, depending on your environment. But the new parameters default to a good compromise and are easier to understand. That's a huge step in the right direction.

## References

### How we Tested

Tests were run with Azul JDK 8 and OpenJDK 10 on a 32 GB MacBook Pro (15-inch, 2018).

```shell
$ use-java10
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-10.0.2.jdk/Contents/Home
$ java -version
openjdk version "10.0.2" 2018-07-17
OpenJDK Runtime Environment 18.3 (build 10.0.2+13)
OpenJDK 64-Bit Server VM 18.3 (build 10.0.2+13, mixed mode)

$ use-java8
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
$ java -version
openjdk version "1.8.0_181"
OpenJDK Runtime Environment (Zulu 8.31.0.1-macosx) (build 1.8.0_181-b02)
OpenJDK 64-Bit Server VM (Zulu 8.31.0.1-macosx) (build 25.181-b02, mixed mode)
```

Results may depend on JVM versions and vendors.

### Further Reading

- <http://royvanrijn.com/blog/2018/05/java-and-docker-memory-limits>
- <https://blog.csanchez.org/2017/05/31/running-a-jvm-in-a-container-without-getting-killed/>
- <https://bugs.openjdk.java.net/browse/JDK-8186315>
- <https://bugs.openjdk.java.net/browse/JDK-8189497>
- <https://docs.openshift.com/container-platform/3.9/dev_guide/application_memory_sizing.html>
- <https://jaxenter.com/better-containerized-jvms-jdk-10-140593.html>
- <https://jaxenter.com/nobody-puts-java-container-139373.html>
- <https://stackoverflow.com/questions/39717077/how-do-i-start-a-jvm-with-unlimited-memory>
- <https://stackoverflow.com/questions/49854237/is-xxmaxramfraction-1-safe-for-production-in-a-containered-environment/50261206#50261206>
- <https://www.reddit.com/r/java/comments/8jkt6h/java_and_docker_the_limitations/?st=jh82hof9&sh=5f385f3d>

[^8u131_release]: <https://blogs.oracle.com/java-platform-group/java-se-support-for-docker-cpu-and-memory-limits>
[^8u131_release_notes]: <https://www.oracle.com/technetwork/java/javase/8u131-relnotes-3565278.html>
[^cgroup]: <https://en.wikipedia.org/wiki/Cgroups>
[^docker_vm]: Keep in mind that if you run Docker in a VM (e.g. on OS X) you will see the resources of your VM, not your physical machine.
[^java10_maxramchanges]: Java 10 deprecates all `-XX:{Initial|Min|Max}RAMFraction` flags and introduced the corresponding `-XX:{Initial|Min|Max}RAMPercentage` flags.
[^java11_removed]: removed in Java 11
[^jvm_overhead]: <https://developers.redhat.com/blog/2017/04/04/openjdk-and-containers>
[^load_tests]: <https://github.com/atamanroman/writing/tree/master/articles/2018/container-java-memory/loadtest>
[^maxram]: Please note that the MaxRAM value shows the maximum amount of memory possible for the given architecture (32/64 bit).
[^nat_mem_track]: <https://docs.oracle.com/javase/8/docs/technotes/guides/troubleshoot/tooldescr007.html>
[^small_ram]: This changes for small RAM values. On my machine, the JVM uses MaxRAMFraction=2 if there is <= 256 MB RAM and MaxRAMFraction=1 if there is <= 8 MB RAM.
[^usecontainersupport]: enabled per default on servers, Linux only
[^xss_sizing]: <https://www.oracle.com/technetwork/java/hotspotfaq-138619.html#threads_oom>
