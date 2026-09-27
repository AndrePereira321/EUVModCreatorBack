# CLAUDE.md — EUVModCreatorBack

Java + Spring Boot API for the EU5 mod creator. Workspace context — what the app is for, how Andre wants to work,
commit message rules — is in the parent `../CLAUDE.md`, which loads alongside this file.

## `.ai-support/` docs

Backend notes too long for this file — the reasoning behind the rules here. Convention: `../CLAUDE.md`.
**Keep this index in sync.** A file added, renamed or deleted in `.ai-support/` is reflected here in the same change.

- [Auth](.ai-support/auth.md) — endpoints, the token, cookie and session design, how a request gets the current
  user, rate limiting, the login lockout and the concurrency limits, the session cleanup job, what the frontend must
  do, where a cache would go, and why login, refresh, logout, usernames and passwords work the way they do.
- [Schema conventions](.ai-support/schema-conventions.md) — column types, where validation rules live, keys,
  indexing (including case-insensitive uniqueness) and hash storage for Flyway migrations. Figures measured, not
  recalled.

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
Flyway 12 · Hibernate 7 · Lombok · Bucket4j 8.20 · Caffeine 3.

Bucket4j is the one dependency Boot doesn't manage: its version is pinned in the pom's `bucket4j.version` property.

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
└─ application-test.properties          <- test profile: the `test` schema, no JWT key (generated per run), no cron
```

**Package by feature, then by role inside the feature.** Top-level packages are features (`auth`); inside one, the
controller and service sit at the feature root, and the rest splits into role sub-packages:

```
auth/
├─ AuthController, AuthService, UserController, UserService
├─ dto/         <- request and response records, never entities
├─ entity/      <- @Entity classes
├─ exception/   <- the feature's ApiException subclasses
├─ model/       <- records a service hands its controller, never an HTTP body (AuthResult, UserSummary)
├─ repository/  <- Spring Data interfaces, and the records their queries return (LoginCredentials)
└─ security/    <- SecurityConfig, AuthProperties, TokenService, the current-user principal, the 401 entry point,
                   the rate limits (RateLimitConfig, RateLimitProperties)
```

Java has no sub-package visibility, so anything used across these folders must be `public`; keep package-private
whatever stays in one folder (`AuthService`, `AuthProperties`). No project-wide `controller/` or `service/` packages.
Code every feature shares gets its own top-level package instead: `error/` (API error handling), `validation/`
(custom Bean Validation constraints), `database/` (`BaseEntity`), `web/` (`WebConfig`, the Spring MVC settings every
controller shares), `ratelimit/` (`RateLimiter`, `Lockout`, the interceptor and the 429; each feature declares its own
limits).
After moving classes between packages, run `./mvnw clean` (or Rebuild in IntelliJ): stale `.class` files from the
old package stay in `target/` and fail startup with `share the entity name`.

## Configuration and profiles

`application.properties` sets `spring.profiles.default=local`, so a plain run picks local without anyone naming
it; production activates explicitly with `SPRING_PROFILES_ACTIVE=production`. Profile files **override** the base
file, they do not replace it. Neither `spring.profiles.active` nor `spring.profiles.default` may appear inside a
profile-specific file — Spring rejects that at startup.

`application-local.properties` is **not in git** because it holds the local JWT signing key. A fresh clone copies
`application-local.properties.example` to it and fills in `euv-app.auth.jwt.secret` before running the app. Tests don't
need it: they run on the `test` profile (see Tests).

`application-production.properties` deliberately has **no fallback values** (`${DATABASE_URL}`, not
`${DATABASE_URL:jdbc:...}`). A missing env var must kill startup rather than quietly boot against localhost.

**Every property of ours sits under `euv-app.`** (`euv-app.auth.jwt.secret`, `euv-app.web.cors.allowed-origins`), in a
`@ConfigurationProperties` prefix and in a `${...}` placeholder alike, so it can never clash with a key of Spring's or
a library's; `ConfigurationPropertiesPrefixTest` checks the records. Production maps each env var by name in
`application-production.properties` (`JWT_SECRET`, `CORS_ALLOWED_ORIGINS`) rather than relying on relaxed binding's
generated names (`EUVAPP_AUTH_JWT_SECRET`).

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
- `@BeforeEach` also resets every `RateLimiter` and `Lockout` bean: every request comes from 127.0.0.1, so the
  counts would otherwise carry over into later tests as 429s. A new limiter bean is covered without editing this.
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
- **Validation rules live on request DTOs, not in `check` constraints.** One copy per rule. The schema keeps only
  what Java can't guarantee: `not null`, uniqueness, foreign keys.
- **Migrations are versioned by date, not a running counter.** `V2026.09.08_001__create_users.sql`, `_002` for
  the next one that day. Versions compare numerically, so pad every part identically (`V2026.9.8_1` duplicates
  `V2026.09.08_001`) and never start a day at `_000` (trailing zero parts are stripped).
- **Auth is JWT plus a server-side session row** — see the Auth section below.
- **CORS rules live in Spring Web (`WebConfig.addCorsMappings`), and Spring Security applies them** through
  `.cors(withDefaults())`. It must: a preflight carries no token, so without it Security answers the preflight with
  a 401. Origins come from `euv-app.web.cors.allowed-origins` (`CORS_ALLOWED_ORIGINS` in production), never `*`, since
  requests carry credentials. An empty list is the off switch and rejects every cross-origin request with a 403.
  The browser's CORS error reads like an auth failure and is not.
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
  only, nothing the user may not see. Not `@ResponseStatus`, which produces no code. `detail` is English for
  developers; never show it to users, never put exception internals in it. `SecurityConfig` permits
  `DispatcherType.ERROR`, or Tomcat's forward to `/error` turns every 4xx into a 401. The security filters run
  before `DispatcherServlet`, out of the advice's reach: `AccessTokenEntryPoint` passes their 401 to the MVC
  `HandlerExceptionResolver`, so it comes out of `GlobalExceptionHandler` too.

## Auth

A short-lived JWT access token, plus a refresh token in an `HttpOnly` cookie whose hash sits in `user_sessions`.
The design, the endpoints and the reasoning behind every rule below are in [auth](.ai-support/auth.md) — read it
before changing anything in `auth/`.

- `euv-app.auth.jwt.secret` never gets a default in a committed file, and must decode to at least 32 bytes;
  `AuthProperties` checks that at startup.
- BCrypt never runs inside a transaction, which holds a pooled connection from its start: `register` hashes before
  `TransactionOperations.execute`, and `login` has no `@Transactional`.
- CSRF is off, which is only safe while the refresh cookie is `SameSite`.
- Refresh never extends a session: the rotated token keeps the session's `expires_at`. It rotates the hash on the
  same row, inside a `@Transactional` method, and `findByRefreshTokenHash` keeps its `@Lock`; without the lock, two
  concurrent refreshes both succeed.
- Login revokes the session of the cookie it replaces, after the password check, through the bulk
  `revokeByRefreshTokenHash`. Not `findByRefreshTokenHash`: its `@Lock` needs a transaction, and `login` has none.
- A bulk `@Modifying` update sets `updatedAt` itself: `@UpdateTimestamp` only fires when Hibernate flushes an entity.
- `SessionCleanupJob` deletes sessions that ended more than `euv-app.auth.session-cleanup.retention` (30d) ago, daily at
  05:00 UTC. Its cron lives in `SessionCleanupProperties`, so the job registers it through `SchedulingConfigurer`:
  `@Scheduled` can't read a bean. Tests set the cron to `-` and call the job directly.
- Logout never fails: 204 and a cleared cookie, whatever the token. The clearing cookie comes from the same
  `refreshTokenCookie` builder as the real one; with a different name or path the browser keeps the real one.
- A controller gets the caller as `@CurrentUser AuthenticatedUser` (user id from `sub`, session id from `sid`) and
  passes the ids to services as parameters. `AuthenticatedUserConverter` builds it from the token alone, never from
  the database, and rejects a bad token with an `AuthenticationException` subclass; anything else becomes a 500.
- Access tokens are checked by signature and `exp` only, so revoking a session takes up to `access-token-ttl` to
  reach them. `issueAccessToken` takes the session's `expiresAt` and caps `exp` at it, so a token never outlives its
  session. The decoder's validators are `JwtValidators.createDefaultWithValidators(new JwtTimestampValidator(Duration.ZERO))`
  — the defaults with no clock skew; a validator added goes into that call, and the `JwtTimestampValidator` stays,
  since it is what checks `exp`.
- `/api/auth/**` ignores the `Authorization` header: `SecurityConfig`'s `BearerTokenResolver` returns no token there.
  The bearer filter runs before `permitAll` is consulted and would answer a stale token with a 401, which broke
  refresh and logout for a frontend that sends its expired token along.
- `UserService.findUser` returns a record, never the entity, so a cache can go on it later.
- `@Qualifier` on a constructor parameter needs a hand-written constructor: Lombok's `@RequiredArgsConstructor`
  drops it.
- Username lookups filter on `lower(username)` in a hand-written `@Query`, under a name Spring Data can't derive
  (`usernameExists`); a derived `...IgnoreCase` compiles to `upper()` and skips the index.
- No `@OneToOne` from `User` to `UserAuth`: it would load the password hash with every user. `UserAuthRepository`
  extends `Repository`, not `JpaRepository`, so no `findAll()` returns hashes either.
- The login lockout keys on the submitted username, lowercased, never the user id — keyed on the id, a 429 would
  confirm an account exists — and runs before BCrypt.
- `login` and `register` carry `@ConcurrencyLimit(policy = REJECT)`: 16 and 2 calls at once per instance
  (`euv-app.auth.rate-limit.login-concurrency`, `register-concurrency`), then 503 `server_busy`. It works through a
  proxy like `@Transactional`, so it needs `@EnableResilientMethods` and never counts a call from inside
  `AuthService`. The annotations read placeholders held in `RateLimitProperties`, which binds the same keys so a bad
  value stops startup instead of the first login.

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
