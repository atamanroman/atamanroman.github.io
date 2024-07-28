---
layout: post
title: Robust Error Handling in Java with Error Values
categories: [development]
tags: [java]
draft: true
---

As a (mostly) Java web developer, every few months I have to fix a very special bug: the web UI displays the wrong error message!
Instead of showing the actual error like "Username is already taken", it shows a generic "Something went wrong".
The reason for this is usually a change in the HTTP error response.
So why does this happen?
And how can we prevent this from happening in the future?

## Showing Errors to the User

There are two common ways how errors are handled in web apps.
A naive approach is to get more and more creative over time with HTTP status codes and map them for every endpoint in the frontend to error messages.
But some day in your career, you will run out of HTTP status - or you'll get tired of the discussion.
Then you might move to something like [Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc7807).
This allows you to keep reusing more generic error status like 400 Bad Request for validation and 401 Unauthorized/403 Forbidden for permission related problems and pair them with error messages or error IDs, depending on your requirements.

Either way, you need to map errors in your code to an HTTP response.
The prevalent frameworks/standards like Spring and JAX-RS use a concept called _Exception Mappers_ for this:

```java
// somewhere in the domain
boolean alreadyExists = userRepo.userExists(newUsername);
if (alreadyExists)
  throw new UserAlreadyExistsException(newUsername)
else
  var user = userRepo.create(newUsername);
// ...

// somewhere in the web/API part
@Provider
public class UserAlreadyExistsExceptionMapper implements ExceptionMapper<UserAlreadyExistsException> {
  @Override public Response toResponse(UserAlreadyExistsException e) {
    return Response.status(CONFLICT).body("%s already exists!".formatted(e.getUsername()));
  }
}
```

So what's wrong with that?
Exception mappers are pretty straight forward and work well - in small, simple codebases.
This is one of the cases where the easy solution won.
It's easy to understand and demos nicely.

But as complexity creeps in, Exception hierarchies might get more complex and the number of Exception mappers rises.
Adding new Exception Mappers in a package "billing" could potentially change the error response of the package "user" because a) API code is often in a central "api" package and b) because `@Provider`s are usually registered globally.

And then the level of layers in the code increases over time, which makes it less obvious which piece of code throws which exception and if the exception is relevant for error handling.
Let's say we suddenly need to get some JSON config from somewhere before we try to create the user.

```java
// somewhere in the domain
try {
  var configJson = fetchCfg();
  // checked exception here
  var config = new ObjectMapper().readValue(configJson, UserCreationConfig.class);

  boolean alreadyExists = userRepo.userExists(newUsername, config.deletedUsernameBlockedTime);
  if (alreadyExists)
    throw new UserAlreadyExistsException(newUsername)
  else
    var user = userRepo.create(newUsername);
  // ...
} catch (Exception e) {
  throw new IllegalStateException("Config must always be valid JSON!", e);
}
```

And it's broken - can you spot it?
The diff looks good and there's a good chance it passes a review[^1].
Whoever is reviewing your change must be aware that the `UserAlreadyExistsException` now is wrapped in an `IllegalStateException` (which usually yields "500: Internal Server Error") and that there's an `UserAlreadyExistsExceptionMapper` in the API package.

I know that this example is a bit constructed.
But I hope it still shows that the distance between throwing the error and handling it, together with the stack escaping nature of exceptions, could make it hard to follow the business logic.

I strongly suggest that exceptions should signal exactly that: unexpected and often unrecoverable technical problems.
A few examples of valid exceptions:

- `IOException` if the disk is full
- `IOException` because the network cable was pulled
- `IllegalStateException` when the JSON config file does contain invalid JSON

And then there are cases where problems are to be expected:

- User input failed validation
- No more money in the bank account
- User tried to sign up with a username that's already taken

In case that something really goes bad (often if IO is happening) then exceptions are perfectly fine.
But if problems are to be expected (likely because the user is involved) I'd try to avoid exceptions for program flow.
In that case, errors (or problems) as values make way more sense because they behave like every other value in Java.

## A Potential Fix: Borrowing From Go

In Go, functions that can error return two values: result or err.

```go
// take any and return a byte array or an error
func Marshal(v any) ([]byte, error) {
  // ...
}

// create two roads
roads := []Road{
    {"Diamond Fork", 29},
    {"Sheep Creek", 51},
  }

// and try to serialize to binary
bytez, err := json.Marshal(roads)
if err != nil {
  log.Fatal(err)
} else {
  fmt.Println(string(bytez))
}
```

## Applying it to Java

It's really easy to do the same in Java:

```java
// very basic Err/Result implementation
public interface Err {
  String error();
}

public record UserAlreadyExistsError(String username) implements Err {
  public String error() {
    return "Username %s is already taken!".format(username);
  }
}

record Result<T>(T value, Err err) {
  public T value() {
    if (err != null) {
      throw new IllegalStateException("You must check for err before accessing the value!")
    }
    return value;
  }

  public boolean isError() {
    return err != null;
  }
}

// somewhere in the domain
String configJson = fetchCfg();
UserCreationConfig config;
try {
  // checked exception here
  config = new ObjectMapper().readValue(configJson.value(), UserCreationConfig.class);
} catch (Exception e) {
  throw new IllegalStateException("Bad application config!", e);
}

boolean alreadyExists = userRepo.userExists(newUsername, config.value().deletedUsernameBlockedTime);
if (alreadyExists)
  return Result.err(new UserAlreadyExistsError(newUsername))
else {
  var user = userRepo.create(newUsername);
  return Result.ok(user);
}
```

Now the error handling is just one method mapping from `Err` to response - no global `@Provider` and no magic.
Exceptions are always fatal and yield "500 Internal Server Error".
It's on us to find the right design - it's ordinary code and can be refactored and moved as such.

```java
class ApiController {
  public Response createUser(String username) {
    var result = userService.createUser(username);
    if(result.isError()) {
      return response(err);
    } else {
      return Response.ok(result.value()).build();
    }
  }

  Response response(Err err) {
    switch(err) {
      case UserAlreadyExistsError -> return Response.status(CONFLICT).body(err.error()).build();
      default -> return Response.status(INTERNAL_SERVER_ERROR).body("Something went wrong").build();
    }
  }
}
```

Not checking for error before accessing the value is always bad - a bit like swallowing exceptions.

Some devs complain that this looks like code from 1987.
And I agree - it's very simple, obvious and there's no chance to be surprised.
This way we're taking control back from the web framework, which is a good thing for important software.

However, the biggest advantage for me is that this makes the error cases way more prominent.
Java code that uses exceptions for business code flow usually looks like there's only the good case.
The exception is often only visible in two locations: where it's thrown and at the boundaries where it's dealt with.
But if we treat errors as values, the good case is pushed in the background and error handling code is more prominent.
And that's awesome, because in the real (programming) world, the number of problematic outcomes is usually way higher than the good ones.

And in my opinion, good code must show this.

## Bonus: Nice Result Classes with xyzxd/dichotomy

[xyzxd/dichotomy](https://github.com/xyzsd/dichotomy) is a tiny library which brings `Result`/`Either`/`Try`/`Maybe` types which are written for modern Java 21 switch expressions with pattern matching.
They work really well for us.

Example:

```java
// from the dichotomy docs
Result<Double,String> result = Result.<Integer, String>ofOK(3828)  // returns an OK<Integer>
       .map(x -> x*10.0)        // map to Result<Double,String>, after multiplying x 10
       .match(System.out::println)     // print "38280.0" to console
       .matchErr(System.err::println);   // ignored, as this is an OK

// [...]

switch(result) {
   case OK(Double x) when x > 0 -> System.out.println("positive");
   case OK(Double x) -> System.out.println("0 or negative");
   case Err(String s) -> System.err.println(s);
}
```

--

^1:
