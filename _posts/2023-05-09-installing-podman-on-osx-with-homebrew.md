---
title: Installing Podman on OS X with Homebrew
categories: [development]
tags: [java, docker, osx]
redirect_from: articles/installing-podman-on-osx-with-homebrew/
---

I've been trying to get away from [Docker Desktop](https://www.docker.com/products/docker-desktop/) for years.
It feels bloated, and I have some mixed feelings about Docker, Inc.

<figure>
  <img src="/static/installing-podman-on-osx-with-homebrew/podman-logo-orig.png" alt="Podman Logo"/>
  <figcaption>The Podman Logo is licensed under <a href="https://github.com/containers/podman.io/blob/main/License">Apache License 2.0</a></figcaption>
</figure>

[Podman](https://podman.io/) looks promising and is usable on OS X since March 2021[^1].
It runs in a local QEMU VM which is managed via _podman machine_, similar to the _docker-machine_ application from a few years ago (which got replaced by Docker for Mac and then Docker Desktop).
Basic container management works well and the VM seems to be fine energy-wise.
And finally, the last blocking bug (for me) has been [fixed](#sidenote-broken-dns-with-earlier-podman-versions-on-os-x) - reason enough to write this blog ✌️.


Here's my setup for a working Podman, Docker client and testcontainers.org setup on macOS Ventura:

```sh
$ brew info podman
==> podman: stable 4.5.0 (bottled), HEAD
Tool for managing OCI containers and pods
[...]
$ brew install podman
# for the docker client (backed by podman)
$ brew install docker
# makes docker work with the podman socket
$ sudo /usr/local/Cellar/podman/4.5.0/bin/podman-mac-helper install
# adjust resources as you like it - this may take some time
$ podman machine init --cpus 4 -m 4096 --now
$ podman run hello-world

Hello from Docker!
$ docker run hello-world

Hello from Docker!
```

Containers are up and running! 🎉

## Enable the OS X Keychain Credential Helper

Install the [docker-credential-helper](https://github.com/docker/docker-credential-helpers) bottle and enable it in Podman[^3] (and optionally Docker).

```sh
$ brew install docker-credential-helper

$ cat ~/.config/containers/auth.json
{
  "credHelpers": {
    "registry.example.com": "osxkeychain"
  }
}

# keychain prompts for credentials on the first login
$ podman login registry.example.com
Authenticating with existing credentials for registry.example.com
Existing credentials are valid. Already logged in to registry.example.com
```

And optionally for _docker_, if you want _docker login_ to behave the same.

```sh
$ cat ~/.docker/config.json
{
  "credsStore": "osxkeychain",
  [...]
}

$ docker login registry.example.com
Authenticating with existing credentials...
Login Succeeded
```


## Additional Setup for [testcontainers.org](https://testcontainers.org)

To get [testcontainers.org](https://testcontainers.org) to work with Podman, the _podman machine_ needs to be rootful and _Ryuk_ must run in privileged mode[^2].

```sh
$ podman machine stop
$ podman machine set --rootful
$ podman machine start
$ cat ~/.testcontainers.properties
ryuk.container.privileged=true
[...]
```

It looks like this is only required by Ryuk[^4], the optional resource reaper component.
So disabling Ryuk might be an option, too.

See also this (little bit dated) [quarkus.io blog post](https://quarkus.io/blog/quarkus-devservices-testcontainers-podman/) about Podman and [testcontainers.org](https://testcontainers.org).

## Sidenote: Broken DNS with Earlier Podman Versions on OS X

An [open bug regarding DNS with _podman login_](https://github.com/containers/podman/issues/16230) made the switch impossible for me, since it broke _podman login_ to my companies' container registry, which is only accessible via VPN.

But, fast-forward a few months, this is fixed!
The current artifacts (> 4.5.0) on GitHub and Homebrew are built with Go > 1.20.x, which handles the [OS X DNS magic with _cgo_ disabled](https://github.com/golang/go/issues/12524).

```text
$ podman version
Client:       Podman Engine
Version:      4.5.0
API Version:  4.5.0
Go Version:   go1.20.3
Git Commit:   75e3c12579d391b81d871fd1cded6cf0d043550a
Built:        Fri Apr 14 15:28:20 2023
OS/Arch:      darwin/amd64

Server:       Podman Engine
Version:      4.5.0
API Version:  4.5.0
Go Version:   go1.20.2
Built:        Fri Apr 14 17:42:22 2023
OS/Arch:      linux/amd64
```

[^1]: <https://github.com/containers/podman/releases/tag/v3.2.0-rc2>
[^2]: <https://github.com/testcontainers/testcontainers-java/issues/2088#issuecomment-1169830358>
[^3]: <https://github.com/containers/podman/issues/4123#issuecomment-888606848>
[^4]: <https://www.testcontainers.org/features/configuration/#customizing-ryuk-resource-reaper>
