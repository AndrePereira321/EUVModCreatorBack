# Modules

How the packages are arranged, what Spring Modulith checks, and the layouts turned down. The rules themselves are in
`../CLAUDE.md` (Layout); this file holds the reasons.

## Package by feature

Top-level packages are features (`auth`) or code every feature shares (`error`, `ratelimit`, `web`, …). The
alternative, project-wide `controller/`, `service/` and `repository/` packages, spreads one feature over every folder,
and each folder grows into a list of everything the app has.

Inside a feature, role sub-packages (`dto/`, `entity/`, …) keep a feature with thirty classes readable. The cost:
Java has no sub-package visibility, so a class used from two of them must be `public`, and Java alone can no longer
tell a feature's API from its internals. Spring Modulith is what tells them apart again.

## What Spring Modulith checks

Test scope only; nothing of it runs in the app. `ModularityTest` calls
`ApplicationModules.of(EuvModCreatorBackApplication.class).verify()`, which reads the compiled classes with ArchUnit
and treats each direct sub-package of `com.euvmodcreator` as a module:

- A module's root package is its API: other modules may use its public types.
- Its sub-packages are internal, `public` or not. Another module using one fails the test.
- No cycles between modules.

Only main code is checked; a test may reach into any module.

When it was added it failed on three cycles, all through `error`. `GlobalExceptionHandler` had a handler for
`RateLimitException` and another for `InvalidAccessTokenException`, each only to add a header, so the shared package
imported two others, and every future header would have meant editing `error/` again. `ApiException.addHeaders` fixed
it: the subclass adds its header, and the one `ApiException` handler applies it.

A sub-package can be opened to other modules with `@NamedInterface` in its `package-info.java`. Prefer moving the type
to the module root: that is why `AuthenticatedUser` and `@CurrentUser` left `auth/security/`, since every controller
of every feature needs them.

## Referencing another feature's data

A mod will belong to a user. `@ManyToOne User owner` would import `auth.entity.User`, an internal type, and fail the
test, rightly: `mod` would depend on auth's table mapping, and loading a mod could load its owner with it. Store
`UUID ownerId` instead, a plain column with a foreign key in the migration; the database doesn't know about modules.
This is Domain-Driven Design's "reference other aggregates by identity".

When `mod` needs something about the owner, such as a name to show, `auth` offers it at its root: a public service
method returning a record, never an entity.

## Turned down

- **A `common/` or `shared/` parent for the shared packages.** Modulith would make it one module, and `common.error`,
  `common.web` and the rest its internal sub-packages, which no feature could use without a `@NamedInterface` on each.
- **Hexagonal folders** (`domain/`, `application/`, `adapter/`). More structure than an app this size pays for.
- **`user/` split from `auth/`.** The two would import each other ([auth](auth.md#me)), a cycle the test now rejects.

## Sources

- [Spring Modulith reference: Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
- [Spring Modulith reference: Verification](https://docs.spring.io/spring-modulith/reference/verification.html)
- [Dan Vega: Introduction to Spring Modulith (2026)](https://www.danvega.dev/blog/2026/04/30/introduction-to-spring-modulith)
