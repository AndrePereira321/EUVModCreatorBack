# CLAUDE.md — EUVModCreatorBack

Java + Spring Boot API for the EU5 mod creator. Workspace context — what the app is for, how Andre wants to work,
commit message rules — is in the parent `../CLAUDE.md`, which loads alongside this file.

## `.ai-support/` docs

Backend notes too long for this file — the reasoning behind the rules here. Convention: `../CLAUDE.md`.
**Keep this index in sync.** A file added, renamed or deleted in `.ai-support/` is reflected here in the same change.

- [Auth](.ai-support/auth.md) — endpoints, the token, cookie and session design, and why login, usernames and
  passwords work the way they do.
- [Schema conventions](.ai-support/schema-conventions.md) — column types, keys, indexing (including
  case-insensitive uniqueness) and hash storage for Flyway migrations. Figures measured, not recalled.

## Commands

Run these from this folder. `JAVA_HOME` is not set system-wide; the JDK is at `~/.jdks/openjdk-25`.

```bash
./mvnw test              # unit + integration tests; integration tests need PostgreSQL running
./mvnw test-compile      # compile only, no database needed
./mvnw spring-boot:run   # start on http://localhost:8080
./mvnw clean package     # build the jar
```

From Git Bash, `export JAVA_HOME=/c/Users/andre/.jdks/openjdk-25` first. From PowerShell use `.\mvnw.cmd`. Andre
normally runs the app from IntelliJ rather than the terminal.

## Stack

Java 25 · Spring Boot 4.1.1 (Spring Framework 7) · Spring Security 7 · Maven wrapper 3.9.16 · PostgreSQL 17 ·
Flyway 12 · Hibernate 7 · Lombok.

**Spring Boot 4 renamed the starters, and everything written online still uses the Boot 3 names.** Do not "fix"
the pom to match a tutorial:

| in this pom (Boot 4)                                  | what tutorials say (Boot 3)                  |
| ----------------------------------------------------- | -------------------------------------------- |
| `spring-boot-starter-webmvc`                          | `spring-boot-starter-web`                    |
| `spring-boot-starter-security-oauth2-resource-server` | `spring-boot-starter-oauth2-resource-server` |
| `spring-boot-starter-<module>-test`                   | `spring-boot-starter-test`                   |

Follow the parent file's Context7 rule before writing non-trivial Spring code, and check `pom.xml` for the
versions actually in use — Boot 4 and Framework 7 are past my training data.

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
└─ application-test.properties          <- test profile: the `test` schema, no JWT key (generated per run)
```

**Package by feature, then by role inside the feature.** Top-level packages are features (`auth`); inside one, the
controller and service sit at the feature root, and the rest splits into role sub-packages:

```
auth/
├─ AuthController, AuthService
├─ dto/         <- request and response records, never entities
├─ exception/   <- the feature's ApiException subclasses
├─ model/       <- @Entity classes
├─ repository/  <- Spring Data interfaces, and the records their queries return (LoginCredentials)
├─ result/      <- records a service hands its controller, never serialized (LoginResult)
└─ security/    <- SecurityConfig, AuthProperties, TokenService
```

Java has no sub-package visibility, so anything used across these folders must be `public`; keep package-private
whatever stays in one folder (`AuthService`, `AuthProperties`). No project-wide `controller/` or `service/` packages.
Code every feature shares gets its own top-level package instead: `error/` (API error handling), `validation/`
(custom Bean Validation constraints), `database/` (`BaseEntity`).
After moving classes between packages, run `./mvnw clean` (or Rebuild in IntelliJ): stale `.class` files from the
old package stay in `target/` and fail startup with `share the entity name`.

## Configuration and profiles

`application.properties` sets `spring.profiles.default=local`, so a plain run picks local without anyone naming
it; production activates explicitly with `SPRING_PROFILES_ACTIVE=production`. Profile files **override** the base
file, they do not replace it. Neither `spring.profiles.active` nor `spring.profiles.default` may appear inside a
profile-specific file — Spring rejects that at startup.

`application-local.properties` is **not in git** because it holds the local JWT signing key. A fresh clone copies
`application-local.properties.example` to it and fills in `auth.jwt.secret` before running the app. Tests don't
need it: they run on the `test` profile (see Tests).

`application-production.properties` deliberately has **no fallback values** (`${DATABASE_URL}`, not
`${DATABASE_URL:jdbc:...}`). A missing env var must kill startup rather than quietly boot against localhost.

Settings that look like candidates for "simplification" and are not:

- `spring.jpa.hibernate.ddl-auto=validate` — Flyway owns the schema, Hibernate only checks entities match it.
  Never change to `update` or `create`; a validation failure is the feature, it means an entity changed without a
  migration.
- `spring.jpa.open-in-view=false` — load what the response needs in the service layer instead of holding a
  connection for the whole request.
- `spring.threads.virtual.enabled=true` — Tomcat handles each request on a virtual thread. This is what lets
  blocking MVC code scale, and it is why WebFlux was rejected.

## Local database

PostgreSQL 17 runs natively at `C:\Program Files\PostgreSQL\17` — no Docker anywhere in this project. Database
`euvmodcreator`, role `euvmodcreator` / password `euvmodcreator`, and that role **owns both the database and the
`public` schema**.

Ownership matters: since PostgreSQL 15 the `public` schema no longer grants `CREATE` to everyone, so a role that
connects fine can still fail the first migration with `permission denied for schema public`.

The role has no `CREATEDB`, which is why integration tests use a `test` **schema** inside this database rather
than a database of their own: owning the database is enough to create a schema, and Flyway does it on first run.

## Tests

Two kinds, both under `./mvnw test`:

- **Unit tests** — plain JUnit, no Spring context, milliseconds. Validation rules through a bare `Validator`,
  services with Mockito mocks, error handling through a standalone `MockMvcTester`. Anything that is logic, not
  wiring.
- **Integration tests** — extend `IntegrationTest`, which starts the whole app on a random port and sends real HTTP
  through `RestTestClient` (Spring Framework 7). Real Tomcat, security filters and PostgreSQL, because the bugs so
  far lived between layers — MockMvc skips the servlet container, so it can't see e.g. the `/error` forward. Every
  endpoint gets one: each status, each error `code`, and a database check where HTTP can't show the result.

`IntegrationTest` owns the plumbing — don't repeat it in subclasses:

- `@ActiveProfiles("test")` → `src/test/resources/application-test.properties`, pointing at the `test` schema, so
  tests never touch dev data.
- A random JWT secret per run through `@DynamicPropertySource`, so no key sits in a committed file.
- `@BeforeEach` truncates every table in the schema except Flyway's. `@Transactional` rollback can't replace
  this: with a real port the server commits each request on its own thread.
- All subclasses share one started app (Spring caches the context), so a new test class costs no startup time
  unless it changes the configuration — avoid `@MockitoBean` and extra properties in integration tests.

A custom `ConstraintValidator` must be `public`: Spring can create a package-private one, plain Hibernate
Validator can't, so it works in the app and throws `NoSuchMethodException` in a unit test.

Mockito's `@InjectMocks` calls the constructor but not `@PostConstruct` — only Spring does. A unit test of a bean
with one calls it itself, as `AuthServiceTest` does with `AuthService.init()`.

## Decisions already made

Don't reopen these without a reason:

- **Spring MVC, not WebFlux.** One backend, a few hundred users, file generation as the real workload. Virtual
  threads cover the concurrency; reactive types would cost readable stack traces and forbid blocking JDBC.
- **Spring Data JPA, not Spring Data JDBC or raw `JdbcClient`.** JPA is what the docs and answers Andre will find
  all assume. `JdbcClient` is still available for queries where JPA gets in the way.
- **Flyway owns the schema, not Hibernate.** See `ddl-auto` above.
- **Migrations are versioned by date, not a running counter.** `V2026.09.08_001__create_users.sql`, `_002` for
  the next one that day. Versions compare numerically, so pad every part identically (`V2026.9.8_1` duplicates
  `V2026.09.08_001`) and never start a day at `_000` (trailing zero parts are stripped).
- **Auth is JWT plus a server-side session row** — see the Auth section below.
- **CORS is Spring Web, not Spring Security.** The Vite dev server on `localhost:5173` calling `localhost:8080`
  needs a `WebMvcConfigurer` with `addCorsMappings`. The browser error reads like an auth failure and is not.
- **Errors are RFC 9457 Problem Details carrying a `code`; the frontend translates, the backend never does.**
  `GlobalExceptionHandler` (`@RestControllerAdvice`) gives every error response a stable `code` — `snake_case`,
  namespaced by feature for domain errors (`auth.username_taken`), derived from the status for Spring MVC's own
  (`method_not_allowed`). Validation failures add `errors: [{field, code, params}]`, where `code` is the constraint
  name (`Size`) and `params` its attributes (`min`, `max`). A domain error is a subclass of `ApiException` with its
  status, code and optional `params` map, sent as a top-level `params` for the translation to interpolate — data
  only, nothing the user may not see. Not `@ResponseStatus`, which produces no code. `detail` is English for
  developers; never show it to users, never put exception internals in it. `SecurityConfig` permits
  `DispatcherType.ERROR`, or Tomcat's forward to `/error` turns every 4xx into a 401.

## Auth

A short-lived JWT access token, plus a refresh token in an `HttpOnly` cookie whose hash sits in `user_sessions`.
The design, the endpoints and the reasoning behind every rule below are in [auth](.ai-support/auth.md) — read it
before changing anything in `auth/`.

- `auth.jwt.secret` never gets a default in a committed file.
- CSRF is off, which is only safe while the refresh cookie is `SameSite`.
- Username lookups filter on `lower(username)` in a hand-written `@Query`; a derived `...IgnoreCase` compiles to
  `upper()` and skips the index.
- No `@OneToOne` from `User` to `UserAuth`: it would load the password hash with every user.

## IntelliJ gotchas

The module is registered in the workspace-root `../.idea/modules.xml`, while its `EUVModCreatorBack.iml` sits in
this folder; `.gitignore` excludes both `.idea/` and `*.iml`, so no IDE config is version-controlled here. (The
frontend repo does track its `.iml` — the two repos differ on this.)

**The IntelliJ module name must equal the pom's `artifactId`.** IntelliJ syncs the two in *both* directions —
giving the module a friendlier name rewrote `<artifactId>` to it and broke the build with `'artifactId' with
value 'Back End' does not match a valid id pattern`. Module name, `.iml` filename, and `artifactId` are all
`EUVModCreatorBack`, and `../.idea/compiler.xml` references that same name for the Lombok annotation profile and
the `-parameters` javac flag. Rename in one place and all four must follow.

**Edit `../.idea/*.xml` only while IntelliJ is closed** — it holds the project model in memory and rewrites those
files on exit, silently discarding external edits.
