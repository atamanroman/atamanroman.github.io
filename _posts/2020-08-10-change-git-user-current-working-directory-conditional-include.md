---
title: Change Your Git User Depending on the Current Working Directory with Conditional Includes
category: development
tags: [git, terminal, tooling]
# TODO link layout?
link: https://motowilliams.com/2017-05-11-conditional-includes-for-git-config
---

Git 2.13+ introduced [Conditional Includes](https://git-scm.com/docs/git-config#_conditional_includes) which allow to set the Git user name depending on the Git repo location (and much more).

The following example defaults my Git user to _Kevin \<k@example.com\>_ and switches to _Vincent Adultman \<va@example.com\>_ if the repo is located in _~/job_.

```
#~/.gitconfig

[include]
  path = ~/.dotfiles/git/private.gituser
[includeIf "gitdir/i:~/job/"]
  path = ~/.dotfiles/git/job.gituser
# ...
```

```
# ~/.dotfiles/git/job.gituser

[user]
  name="Kevin"
  email="k@example.com"
```

```
# ~/.dotfiles/git/private.gituser

[user]
  name="Vincent Adultman"
  email="va@example.com"
```

(First seen at <https://motowilliams.com/2017-05-11-conditional-includes-for-git-config/>)
