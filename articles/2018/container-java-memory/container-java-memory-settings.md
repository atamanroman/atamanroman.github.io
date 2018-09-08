---
title: JVM Memory Settings in a Container Environment
tags:
canonicalUrl:
publishStatus: draft
license: all-rights-reserved
---

# JVM Memory Settings in a Container Environment

## Who are we and why do we care?

[Joe](https://github.com/jkroepke) and [I](https://twitter.com/atamanroman) both do a lot of work with Java on OpenShift/Kubernetes. Joe has a very strong operations background and I feel more at home with software development — yet we often do similar stuff. We also like to ~~argue~~ work together and this article is the result of one particularly interesting discussion about the JVM setting `+UseCGroupMemoryLimit`. We stitched this article together during a few Fridays at the office at [adorsys](https://adorsys.de).

---

## TL;DR

Java memory management and configuration is still complex. Although the JVM can read cgroup memory limits and adapt memory usage accordingly since Java 9/8u131, it's not a golden bullet. You need to know what `-XX:+UseCGroupMemoryLimitForHeap` does and you need to fine tune some parameters for every deployment. Otherwise you risk wasting resources and money or getting your containers killed at the worst time possible. Java 10 brings some improvements but still does not solve everything. To be safe, load test your stuff.

---

## What's the Problem?

Tweaking the JVM memory settings when deploying on a container platform is not trivial. Applications running in a Docker container always see the full resources available. cgroup[^cgroup] limits are just not visible that way. See for yourself[^docker_vm_note]:

```
$ docker run -it --rm alpine free -m
             total     used     free   shared  buffers   cached
Mem:          1998     1565      432        0        8     1244
```

```
$ docker run -it --rm -m 256m alpine free -m
             total     used     free   shared  buffers   cached
Mem:          1998     1552      445        1        8     1244
```

Because it sees *a lot* of available resources on your expensive Kubernetes node it tries to hog a good amount of it. This egoism makes sense when the JVM can own a good chunk of the underlying hardware (e.g. a dev workstation or an single purpose VM).

But it makes the JVM behave quite badly on container platforms per default. If the resources are shared between unrelated, more or less equally important processes, the egoistic resource allocation can cause problems. Depending on your current setup, one of two things can happen: a) the JVM is killed as soon as it tries to allocate more memory than it is allowed according to the quotas set in your deployment config or b) the JVM eats your precious resources for breakfast if there are no (hard[^quota_types]) quotas.

So what's the fix? We *could* configure the JVM manually. Or make it cgroup aware — which is exactly what has already been done. The first cgroup related patches landed with Java 9 and were backported to Java 8u131[^8u131_release] in April 2017[^8u131_release_notes]. We'll have a closer look at these new options soon.

## Where the JVM Goes to Get its Heap

Per default the JVM automatically configures heap size according to the spec of the machine it is running on. On my brand new MacBook Pro 2018 this yields the following heap size:

```
<~/D/w/a/2/src>-> java -XX:+PrintFlagsFinal -version | grep -Ei "maxheapsize|maxram"
    uintx DefaultMaxRAMFraction   = 4             {product}
    uintx MaxHeapSize             := 8589934592   {product}
    uint64_t MaxRAM               = 137438953472  {pd product}
    uintx MaxRAMFraction          = 4             {product}
```

As you can see, the JVM defaults to 8.0 GB max heap `(8589934592 / 1024^3)` and 0.5 GB initial heap on my machine. The formula behind this is straight forward. Using the JVM configuration parameter names, we end up with: `MaxHeapSize = MaxRAM * 1 / MaxRAMFraction` where MaxRAM is the available RAM[^maxram_note] and MaxRAMFraction is 4[^small_ram_note] by default. That means the **JVM allocates up to a fourth of your RAM per JVM** running on your machine.

It's important to note that the JVM uses more memory than what it allocates for heap. We can calculate the total memory usage roughly with `heap + stack per thread (XSS) * threads + constant overhead`.
The default for XSS depends on the OS and JVM and is somewhere between 256 KB and 1 MB[^xss_sizing]. That means: every thread allocates at least 256 KB additional memory.
The constant overhead is all memory allocated by the JVM which is not heap or stack. This value depends on a lot of factors. See [^jvm_overhead] and -XX:NativeMemoryTracking[^nat_mem_track] for more details. (TODO correctness!)

## Taking Over Heap Configuration

Hand tuning heap size is nothing new. Usually *MaxHeap* (`-Xmx`) is set to a fixed, more or less carefully hand-rolled number. Our Hello World application should be fine with way less heap. Let's check what happens.

```
<~/D/w/a/2/src>-> java -XX:+PrintFlagsFinal -Xmx1g -version | grep -Ei "maxheapsize|maxram"

    uintx DefaultMaxRAMFraction   = 4            {product}
    uintx MaxHeapSize             := 1073741824  {product}
    uint64_t MaxRAM               = 137438953472 {pd product}
    uintx MaxRAMFraction          = 4            {product}
```

64 MB initial and still running smoothly. And there are other ways how to set the heap, too. We can adjust `MaxRAM`, effectively simulating a smaller machine:

```
 <~/D/w/a/2/src>-> java -XX:+PrintFlagsFinal -XX:MaxRAM=1g -version | grep -Ei "maxheapsize|maxram"
    uintx DefaultMaxRAMFraction   = 4            {product}
    uintx MaxHeapSize             := 268435456   {product}
    uint64_t MaxRAM               := 1073741824  {pd product}
    uintx MaxRAMFraction          = 4            {product}
```

Now the JVM is back in charge calculating the heap size, we just fine tune the parameters. In this case we end up with 256 MB max and 16 MB initial. That's fine for a desktop, but a bit conservative for a dedicated host. If we spend good money on a VPS with 1 GB RAM, we'd like the JVM to make better use of the available resources. Here comes `-XX:MaxRAMFraction` into play. This positive integer controls how much of the total RAM is up for grabs. `1/MaxRAMFraction` yields the percentage of RAM we can use for heap.

| Fraction | % of RAM for heap |
|:--|:--|
| 1 | 100% |
| 2 | 50% |
| 3 | 33% |
| 4 | 25% |

So for our dedicated 1 GB server it's enough to set `-XX:MaxRAMFraction=2` and we end up with 16 MB initial and 512 MB max.

```
# -XX:MaxRAM is only set for the sake of this example to simulate a smaller physical machine
<~/D/w/a/2/src>-> java -XX:+PrintFlagsFinal -XX:MaxRAM=1g -XX:MaxRAMFraction=2 -version | grep -Ei "maxheapsize|maxram"
    uintx DefaultMaxRAMFraction   = 4            {product}
    uintx MaxHeapSize             := 536870912   {product}
    uint64_t MaxRAM               := 1073741824  {pd product}
    uintx MaxRAMFraction          := 2           {product}
```

That looks pretty good for prod!

## JVM cgroup memory magic demystified

To make the JVM play well with cgroup memory limits a new option `-XX:+UseCGroupMemoryLimitForHeap` was introduced with Java 9 and backported to Java 8u131. It sounds fancy but its effect is pretty simple once you know the basics. It allows setting the heap according to the cgroup memory limit. The JVM reads the limit from `/sys/fs/cgroup/memory/memory.limit_in_bytes` and sets `-XX:MaxRAM` to this value.

```
<~/D/w/a/2/src>-> docker run -it --rm -m 256m alpine cat /sys/fs/cgroup/memory/memory.limit_in_bytes
268435456
```

```
<~/D/w/a/2/src>-> docker run -it --rm -v "$PWD/Hello.class:/Hello.class" -m 1g openjdk:8-jdk sh -c "java -XX:+PrintFlagsFinal -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -version | grep -Ei 'maxheapsize|maxram'"
    uintx DefaultMaxRAMFraction   = 4             {product}
    uintx MaxHeapSize            := 268435456     {product}
    uint64_t MaxRAM               = 137438953472  {pd product}
    uintx MaxRAMFraction          = 4             {product}
```

And that's it. 1 GB memory quota yields 256 MB heap. Exactly the same as in the non-docker examples. Note that `-XX:+UseCGroupMemoryLimitForHeap` requires `-XX:+UnlockExperimentalVMOptions` in order to work. And there's a good reason to it.

## Digging Into UseCGroupMemoryLimitForHeap

So the JVM is now aware of cgroup memory limits - you just need to enable that feature. Are we done here? We thought so at first. If you search for *java container heap configuration prod* or something like that, almost every blog post will advise you to set that flag and have a nice day. Happy end.

But then I encountered some strange behaviour in our development stage. Because we were low on available memory on the development nodes (*cough* cloud), I limited some very simple services to 128 MB. And then they died. *OOMKilled*. Which means the container tried to allocate more than 128 MB. With what I knew back then, that made no sense. The JVM had the information that there was only 128 MB available and yet memory consumption was higher. If anything, I'd have expected an `OutOfMemoryError` if the heap was really too tight.

That's when I got in touch with Joe. I explained my situation and we were both surprised. We dug through a lot of documentation[^java_docker_references] to really understand all the basics you've just read. Then we deployed some simple load tests[^load_tests] to verify our assumptions. And they got OOMKilled.

### Why +UseCGroupMemoryLimitForHeap is not a Magic Bullet

Getting OOMKilled is not a good thing. Your app can't react to in any way - you just get killed. In my opinion, this should never happen. I always want to get an `OutOfMemoryError` when heap runs out so I can get a heap dump and analyse it. `OOMKilled` should only happen if there is something *really broken*, like a memory leak in the JVM. That is necessary to protect other deployments on the same node. If your service gets killed under heavy load there higher risk that other instances go down, too. This can snowball and kill your whole system, which otherwise might have worked pretty ok if the resources were set and limited properly. The worst thing is that this class of bug only occurs under heavy load, which is the worst time to fail. Often it does not happen until the product is out there for a while and has enough visibility for a three day outage to be newsworthy.

So why does this happen? As it turns out, setting `-XX:MaxRAM` to the cgroup memory limit is not enough. Depending on the actual limit, you can run into efficiency or stability issues.

You may have wondered about `-XX:MaxRAMFraction` before. The fact that it has to be a positive integer makes that setting pretty coarse. You can choose between 25%, 33%, 50% and 100% system RAM as heap (a lower percentage is possible, but seems not very practical). And that's the problem. The default of 25% (50% for small values of `MaxRAM`) is not resource efficient for large heaps. You just waste too much RAM. If you dig deeper online, you'll find a few blogs suggesting to set `-XX:MaxRAMFraction=1`, so that the JVM can use all of the RAM for heap. That sounds clever.

Now try to recall how the JVM memory consumption is calculated. We **must** have enough headroom for `threads * XSS` and some constant amount. Also there is often other stuff in a container which might allocate some memory (I've seen SSHd, monitoring processes, the shell which spawned your process, ...). And last but not least you might want to have enough free memory to be actually able to `docker exec` into your container to trigger a heap dump, attach a debugger or do some other debugging stuff. Whether this is actually necessary depends a lot on the level of automation you have in place. But you should consider it when configuring your deployments.

On the other hand, even 50% can be too small for very small values of `MaxRAM`. You will run into the same problems as with `-XX:MaxRAMFraction=1` and may get OOMKilled.

## Different Configurations and their Effect

Here we go over the most common configurations out there and try to identify safe and bad ones. Remember that it makes no difference if MaxRAM is set manually or via `+UseCGroupMemoryLimitForHeap`.

| MaxRAM | MaxRAMFraction | Memory Efficiency | Risk of Being Killed | Likely Good Enough |
|:--------------|:--|:---|:---|:---|
| <= 256 MB     | 4 | +  | /  | 👍 |
| <= 256 MB     | 2 | ++ | +  | 👍 |
| <= 256 MB     | 1 | ++ | ++ | 👎 |
| 256 MB - 1 GB | 4 | -  | -  | 👎 |
| 256 MB - 1 GB | 2 | /  | /  | 👍 |
| 256 MB - 1 GB | 1 | ++ | ++ | 👎 |
| 1 GB - 4 GB   | 4 | -- | -- | 👎 |
| 1 GB - 4 GB   | 2 | -  | -- | 👍 |
| 1 GB - 4 GB   | 1 | ++ | ++ | 👎 |
| > 4 GB        | 4 | -- | -- | 👎 |
| > 4 GB        | 2 | -- | -- | 👎 |
| > 4 GB        | 1 | ++ | ++ | 👎 |

*Very high = ++, OK-ish = /, very small = --*

Remember that this is general advice, based on our experience with Java systems. A system which wastes resources still does its job. An undersized system with two visitors a day might never experience enough load to go down. Please test your configuration for stability instead of relying on stuff people said on the internet. Yes that means load testing. And no, that's not optional if you want a system which is still functional under load.

TODO summary/fazit zu der tabelle - alles doof

## Possible Fixes

TODO manuelle config, java10, selbst bauen im entrypoint, maxram bescheissen, ...

## What About CPU and Other Quotas?

TODO
CPU quotas derived automatically from cgroup but not for memory. Why? Reference!

TODO

## References

### My System

Tests were run with Azul JDK 8 and OpenJDK 10 on a 32GB MacBook Pro (15-inch, 2018).

```
<~/D/w/a/2/src>-> use-java10
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-10.0.2.jdk/Contents/Home
<~/D/w/a/2/src>-> java -version
openjdk version "10.0.2" 2018-07-17
OpenJDK Runtime Environment 18.3 (build 10.0.2+13)
OpenJDK 64-Bit Server VM 18.3 (build 10.0.2+13, mixed mode)
<~/D/w/a/2/src>-> use-java8
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
<~/D/w/a/2/src>-> java -version
openjdk version "1.8.0_181"
OpenJDK Runtime Environment (Zulu 8.31.0.1-macosx) (build 1.8.0_181-b02)
OpenJDK 64-Bit Server VM (Zulu 8.31.0.1-macosx) (build 25.181-b02, mixed mode)
```

Results may differ with other JVM versions and vendors.

### Hello World
This Hello-World program was used for testing. Compiled with `javac Hello.java`.

```
class Hello {
  public static void main(String[] args) {
    System.out.println("Hello World");
  }
}
```

### Further Reading

[^8u131_release]: [https://blogs.oracle.com/java-platform-group/java-se-support-for-docker-cpu-and-memory-limits]()
[^8u131_release_notes]: [https://www.oracle.com/technetwork/java/javase/8u131-relnotes-3565278.html]()
[^cgroup]: [https://en.wikipedia.org/wiki/Cgroups]()
[^docker_vm_note]: Keep in mind that if you run Docker in a VM (e.g. on OS X) you will see the resources of your VM, not your physical machine.
[^java_docker_references]: TODO
[^jvm_overhead]: [https://developers.redhat.com/blog/2017/04/04/openjdk-and-containers/]()
[^load_tests]: TODO
[^maxram_note]: Please note that the MaxRAM value seems broken for my machine as it shows 128GB MaxRAM, which is false. The resulting heap, however, is correct (32*1/4 == 8).
[^nat_mem_track]: https://docs.oracle.com/javase/8/docs/technotes/guides/troubleshoot/tooldescr007.html
[^quota_types]: TODO link hard/soft quotas
[^smallram_note]: This changes for small RAM values. On my machine, the JVM uses MaxRAMFraction=2 if there is <= 256 MB RAM and MaxRAMFraction=1 if there is <= 8 MB RAM.
[^xss_sizing]: [https://www.oracle.com/technetwork/java/hotspotfaq-138619.html#threads_oom]()