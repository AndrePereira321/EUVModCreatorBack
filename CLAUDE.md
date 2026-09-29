# CLAUDE.md — EUVModCreatorBack

Java + Spring Boot API for the EU5 mod creator. Workspace context — what the app is for, how Andre wants to work,
commit message rules — is in the parent `../CLAUDE.md`, which loads alongside this file.

## `.ai-support/` docs

Backend notes too long for this file. The convention, and the rule to keep this index in sync: `../CLAUDE.md`.

- [Auth](.ai-support/auth.md) — **read before touching `auth/`, `ratelimit/` or `SecurityConfig`.** Tokens,
  cookie and sessions, rate limits and lockout, session cleanup, what the frontend must do, and why each rule holds.
- [Logging](.ai-support/logging.md) — **read before adding a log line or changing `logging.*`.** Levels, the flood
  rule, the full never-log list, the MDC request id, log files and rotation.
- [Schema conventions](.ai-support/schema-conventions.md) — **read before writing a Flyway migration.** Column
  types, keys, indexing, case-insensitive uniqueness, hash storage. Figures measured, not recalled.
- [Modules](.ai-support/modules.md) — **read before adding a top-level package or having one feature use another.**
  What Spring Modulith checks and why, referencing another feature's data, and the layouts turned down.

## Commands

Run these from this folder. `JAVA_HOME` is not set system-wide: from Git Bash,
`export JAVA_HOME=/c/Users/andre/.jdks/openjdk-25` first; from PowerShell, use `.\mvnw.cmd`. Andre normally runs the
app from IntelliJ.

```bash
./mvnw test              # unit + integration tests; integration tests need PostgreSQL running
./mvnw test-compile      # compile only, no database needed
./mvnw spring-boot:run   # start on http://localhost:8080
./mvnw clean package     # build the jar — Maven runs every test first, so PostgreSQL must be up (-DskipTests skips)
```

## Pre-commit hook

`.githooks/pre-commit` runs `./mvnw -q test-compile` and blocks the commit if main or test code doesn't compile. It
doesn't run the tests — they need PostgreSQL, and a hook that fails whenever the database is down gets bypassed. Run
`./mvnw test` before pushing. The hook sets `JAVA_HOME` to `~/.jdks/openjdk-25` when it's unset, as it is in a commit
from IntelliJ.

**Git only runs it after `git config core.hooksPath .githooks`** — once per clone, since Maven has no install step to
do it the way npm's `prepare` does in the frontend. A new hook file needs the executable bit in git
(`git add --chmod=+x .githooks/<hook>`) or it won't run on Linux or macOS.

## Stack

Java 25 · Spring Boot 4.1.1 (Spring Framework 7) · Spring Security 7 · Maven wrapper 3.9.16 · PostgreSQL 17 ·
Flyway 12 · Hibernate 7 · Lombok · Bucket4j 8.20 · Caffeine 3 · Spring Modulith 2.1 (tests only).

Boot doesn't manage Bucket4j or Spring Modulith: their versions are pinned in the pom's `bucket4j.version` and
`spring-modulith.version` properties. Modulith comes through its BOM; keep it on the line built for the Boot version
in use (2.1 for Boot 4.1).

**Spring Boot 4 renamed the starters, and everything written online still uses the Boot 3 names.** Do not "fix"
the pom to match a tutorial:

| in this pom (Boot 4)                                  | what tutorials say (Boot 3)                  |
| ----------------------------------------------------- | -------------------------------------------- |
| `spring-boot-starter-webmvc`                          | `spring-boot-starter-web`                    |
| `spring-boot-starter-security-oauth2-resource-server` | `spring-boot-starter-oauth2-resource-server` |
| `spring-boot-starter-<module>-test`                   | `spring-boot-starter-test`                   |

## Layout

```
src/main/java/com/euvmodcreator/        <- all Java; EuvModCreatorBackApplication is the entry point
src/main/resources/
├─ application.properties               <- shared config, always loaded
├─ application-local.properties         <- git-ignored: local datasource, dev JWT signing key
├─ application-local.properties.example <- tracked template for the file above
├─ application-production.properties    <- env-var driven, no fallbacks
└─ db/migration/                        <- Flyway migrations, V2026.09.08_001__snake_case.sql
src/test/java/com/euvmodcreator/        <- mirrors main's packages; IntegrationTest is the base for HTTP tests
src/test/resources/
└─ application-test.properties          <- test profile: the `test` schema, no JWT key (generated per run), no cron
```

**Package by feature, then by role inside the feature.** Top-level packages are features (`auth`); inside one, the
controllers, services, config and the types other features may use sit at the feature root, and the rest splits into
role sub-packages:

```
auth/
├─ AuthController, AuthService, UserController, UserService, RateLimitConfig, …
├─ AuthenticatedUser, CurrentUser   <- auth's API: what other features may use
├─ dto/         <- request and response records, never entities
├─ entity/      <- @Entity classes
├─ exception/   <- the feature's ApiException subclasses
├─ model/       <- records a service hands its controller, never an HTTP body (AuthResult, UserSummary)
├─ repository/  <- Spring Data interfaces, and the records their queries return (LoginCredentials)
└─ security/    <- SecurityConfig, tokens, turning a JWT into the current user
```

Java has no sub-package visibility, so anything used across these folders must be `public`; keep package-private
whatever stays in one folder (`AuthService`, `AuthProperties`). No project-wide `controller/` or `service/` packages.
Code every feature shares gets its own top-level package instead: `error/` (API error handling), `validation/`
(custom Bean Validation constraints), `database/` (`BaseEntity`), `web/` (`WebConfig`, the Spring MVC settings every
controller shares), `ratelimit/` (`RateLimiter`, `Lockout`, the interceptor and the 429; each feature declares its own
limits), `logging/` (`MdcFilter`).
After moving classes between packages, run `./mvnw clean` (or Rebuild in IntelliJ): stale `.class` files from the
old package stay in `target/` and fail startup with `share the entity name`.

**Spring Modulith checks the boundaries** ([modules](.ai-support/modules.md)). Each top-level package is a module:
its root package is its API, its sub-packages are internal. `ModularityTest` fails on a cycle between modules or on
one module using another's sub-package. So what another feature may use goes at the feature root; a shared package
never imports a feature; and a feature points at another's entities by id (`UUID ownerId`), never with `@ManyToOne`.

## Configuration and profiles

`local` is the default profile (`spring.profiles.default`); production sets `SPRING_PROFILES_ACTIVE=production`. A
profile file overrides `application.properties`, and may not contain `spring.profiles.active` or `.default`.

- `application-local.properties` is git-ignored because it holds the dev JWT key. A fresh clone copies the
  `.example` next to it and fills in `euv-app.auth.jwt.secret`.
- **Every property of ours sits under `euv-app.`**, in `@ConfigurationProperties` prefixes and `${...}` placeholders
  alike; `ConfigurationPropertiesPrefixTest` checks it. Production maps each env var by name (`JWT_SECRET`) with
  **no fallback value**, so a missing one stops startup instead of booting against localhost.
- Don't "simplify" `spring.jpa.hibernate.ddl-auto=validate` (see Decisions), `spring.jpa.open-in-view=false` (load
  what a response needs in the service layer) or `spring.threads.virtual.enabled=true` (what lets blocking MVC scale).

## Local database

PostgreSQL 17 runs natively at `C:\Program Files\PostgreSQL\17`, no Docker. Database, role and password are all
`euvmodcreator`, and the role **owns the database and its `public` schema**: since PostgreSQL 15, without that the
first migration fails with `permission denied for schema public`. The role has no `CREATEDB`, so integration tests
use a `test` schema in the same database, which Flyway creates on first run.

## Tests

Three kinds, all under `./mvnw test`:

- **Unit tests** — plain JUnit, no Spring context: validation through a bare `Validator`, services with Mockito
  mocks, error handling through a standalone `MockMvcTester`. Anything that is logic, not wiring.
- **Integration tests** — extend `IntegrationTest`: the whole app on a random port, real HTTP through
  `RestTestClient`, real Tomcat and PostgreSQL. Not MockMvc, which skips the servlet container and misses bugs such as
  the `/error` forward. Every endpoint gets one: each status, each error `code`, and a database check where HTTP
  can't show the result.
- **Rule tests** — scan the compiled code for a project rule, with no Spring context or database: `ModularityTest`
  (module boundaries), `ConfigurationPropertiesPrefixTest` (the `euv-app.` prefix).

`IntegrationTest` owns the plumbing — don't repeat it in subclasses:

- The `test` profile, pointing at the `test` schema, and a random JWT secret per run.
- Truncates every table except Flyway's before each test. `@Transactional` rollback can't: the server commits each
  request on its own thread.
- Resets every `RateLimiter` and `Lockout` bean before each test, or counts from 127.0.0.1 carry over as 429s.
- One started app shared by every subclass: avoid `@MockitoBean`, extra properties and Spring Modulith's
  `@ApplicationModuleTest`, which each start another.

Unit-test gotchas: a custom `ConstraintValidator` must be `public` (Spring can create a package-private one, plain
Hibernate Validator can't), and `@InjectMocks` never calls `@PostConstruct` — call it yourself, as `AuthServiceTest`
does with `init()`.

## Decisions already made

Don't reopen these without a reason:

- **Spring MVC, not WebFlux.** One backend, a few hundred users, file generation as the real workload. Virtual
  threads cover the concurrency; reactive types would cost readable stack traces and forbid blocking JDBC.
- **Spring Data JPA, not Spring Data JDBC or raw `JdbcClient`.** JPA is what the docs and answers Andre will find
  all assume. `JdbcClient` is still available for queries where JPA gets in the way.
- **Flyway owns the schema, not Hibernate.** `ddl-auto=validate` only checks that entities match it; a failure means
  an entity changed without a migration.
- **Validation rules live on request DTOs, not in `check` constraints.** One copy per rule. The schema keeps only
  what Java can't guarantee: `not null`, uniqueness, foreign keys.
- **Migrations are versioned by date, not a running counter.** `V2026.09.08_001__create_users.sql`, `_002` for
  the next one that day. Versions compare numerically, so pad every part identically (`V2026.9.8_1` duplicates
  `V2026.09.08_001`) and never start a day at `_000` (trailing zero parts are stripped).
- **Auth is JWT plus a server-side session row** — see [auth](.ai-support/auth.md).
- **A controller gets the caller as `@CurrentUser AuthenticatedUser` and passes its ids to services as
  parameters.** Services never read `SecurityContextHolder`.
- **CORS rules live in `WebConfig.addCorsMappings`; Spring Security applies them through `.cors(withDefaults())`**,
  or it answers a preflight, which carries no token, with a 401. Origins come from
  `euv-app.web.cors.allowed-origins`, never `*`, since requests carry credentials; an empty list rejects every
  cross-origin request with a 403. A browser CORS error reads like an auth failure and is not.
- **Every `@RestController` sits under `/api`, added once by `WebConfig.configurePathMatch`.** Controllers map
  without it (`@RequestMapping("/auth")`). What sees the raw URL still writes it: `SecurityConfig`'s matchers, the
  cookie path, the rate-limit interceptors' path patterns, and the tests. Not `server.servlet.context-path`, which
  would move `/error` and everything else too.
- **Errors are RFC 9457 Problem Details carrying a `code`; the frontend translates, the backend never does.**
  `GlobalExceptionHandler` (`@RestControllerAdvice`) gives every error response a stable `code` — `snake_case`,
  namespaced by feature for domain errors (`auth.username_taken`), derived from the status for Spring MVC's own
  (`method_not_allowed`). Validation failures add `errors: [{field, code, params}]`, where `code` is the constraint
  name (`Size`) and `params` its attributes (`min`, `max`). A domain error is a subclass of `ApiException` with its
  status, code and optional `params` map, sent as a top-level `params` for the translation to interpolate — data
  only, nothing the user may not see. A header it needs (`Retry-After`) comes from overriding `addHeaders`. Not
  `@ResponseStatus`, which produces no code. `detail` is English for developers; never show it to users, never put
  exception internals in it.

## Logging

Conventions and reasoning: [logging](.ai-support/logging.md). The rules any feature can break:

- Never log passwords, tokens, hashes, secrets, headers, request bodies, or any string the client typed, such as the
  username at login. Users and sessions go in by UUID.
- A record holding a secret overrides `toString` to hide it, with a test: Spring MVC's DEBUG logging prints request
  bodies through `toString`.
- Services log domain events; controllers don't. `GlobalExceptionHandler` logs every error response once, so a
  thrown `ApiException` isn't logged again.

## Spring gotchas

- `@Transactional`, `@ConcurrencyLimit` and `@Cacheable` work through a proxy: a call from inside the same class
  skips it, and the method runs without the annotation.
- An exception thrown in a servlet filter never reaches `GlobalExceptionHandler`. Prefer a `HandlerInterceptor`, as
  the rate limits do.
- A bulk `@Modifying` update sets `updatedAt` itself: `@UpdateTimestamp` only fires when Hibernate flushes an entity.
- `@Qualifier` on a constructor parameter needs a hand-written constructor: Lombok's `@RequiredArgsConstructor` drops
  it.
