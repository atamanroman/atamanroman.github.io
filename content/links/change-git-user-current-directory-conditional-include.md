---
title: Change Your Git User Depending on the Current Directory with Conditional Includes
slug: change-git-user-depending-current-directory-conditional-include
date: 2020-08-10
categories: [development]
tags: [git, terminal, tooling]
link: https://www.motowilliams.com/conditional-includes-for-git-config
---

Git 2.13+ introduced [_Conditional Includes_](https://git-scm.com/docs/git-config#_conditional_includes) which allow to set the Git user name depending on the Git repo directory (and much more):

>```sh
> [includeIf "gitdir:~/code/"]
>   path = .gitconfig-personal
> [includeIf "gitdir:~/code/work/"]
>   path = .gitconfig-work
> ```
>
> &mdash; Example from <cite>[Eric William][1]</cite>

[1]: https://www.motowilliams.com/conditional-includes-for-git-config

<!--more-->
