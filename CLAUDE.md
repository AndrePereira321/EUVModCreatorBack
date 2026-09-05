# CLAUDE.md — EUVModCreatorBack

Java + Spring Boot API for the EU5 mod creator. Workspace context — what the app is for, how Andre wants to work,
commit message rules — is in the parent `../CLAUDE.md`, which loads alongside this file.

## Commands

Run these from this folder. `JAVA_HOME` is not set system-wide; the JDK is at `~/.jdks/openjdk-25`.

```bash
./mvnw test              # contextLoads test — starts the full context, so PostgreSQL must be running
./mvnw test-compile      # compile only, no database needed
./mvnw spring-boot:run   # start on http://localhost:8080
./mvnw clean package     # build the jar
```

From Git Bash, `export JAVA_HOME=/c/Users/andre/.jdks/openjdk-25` first. From PowerShell use `.\mvnw.cmd`. Andre
normally runs the app from IntelliJ rather than the terminal.

## Stack

Java 25 · Spring Boot 4.1.1 (Spring Framework 7) · Maven wrapper 3.9.16 · PostgreSQL 17 · Flyway 12 · Hibernate 7
· Lombok.

**Spring Boot 4 renamed the starters, and everything written online still uses the Boot 3 names.** Do not "fix"
the pom to match a tutorial:

| in this pom (Boot 4)                | what tutorials say (Boot 3)  |
| ----------------------------------- | ---------------------------- |
| `spring-boot-starter-webmvc`        | `spring-boot-starter-web`    |
| `spring-boot-starter-<module>-test` | `spring-boot-starter-test`   |

Follow the parent file's Context7 rule before writing non-trivial Spring code, and check `pom.xml` for the
versions actually in use — Boot 4 and Framework 7 are past my training data.

## Layout

```
src/main/java/com/euvmodcreator/       <- all Java; EuvModCreatorBackApplication is the entry point
src/main/resources/
├─ application.properties              <- shared config, always loaded
├─ application-local.properties        <- local datasource
├─ application-production.properties   <- env-var driven, no fallbacks
└─ db/migration/                       <- Flyway migrations, V1__snake_case.sql; empty so far
src/test/java/com/euvmodcreator/
```

Flat by design — no `controller/`, `service/`, `repository/` packages until there is enough code to justify them.

## Configuration and profiles

`application.properties` sets `spring.profiles.default=local`, so a plain run picks local without anyone naming
it; production activates explicitly with `SPRING_PROFILES_ACTIVE=production`. Profile files **override** the base
file, they do not replace it. Neither `spring.profiles.active` nor `spring.profiles.default` may appear inside a
profile-specific file — Spring rejects that at startup.

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

`No migrations found. Are your locations set up correctly?` on startup is expected until the first migration file
exists.

## Decisions already made

Don't reopen these without a reason:

- **Spring MVC, not WebFlux.** One backend, a few hundred users, file generation as the real workload. Virtual
  threads cover the concurrency; reactive types would cost readable stack traces and forbid blocking JDBC.
- **Spring Data JPA, not Spring Data JDBC or raw `JdbcClient`.** JPA is what the docs and answers Andre will find
  all assume. `JdbcClient` is still available for queries where JPA gets in the way.
- **Flyway owns the schema, not Hibernate.** See `ddl-auto` above.
- **No Spring Security yet.** Login is planned. Adding the starter early puts every endpoint behind a generated
  password before there is anything to protect. When it lands: **session cookies, not JWT** — one backend,
  server-side revocation, `HttpOnly` beats a token in browser storage. "Sign in with Discord" via OAuth2 Client
  is worth weighing first, given the audience.
- **CORS is Spring Web, not Spring Security.** The Vite dev server on `localhost:5173` calling `localhost:8080`
  needs a `WebMvcConfigurer` with `addCorsMappings`. The browser error reads like an auth failure and is not.

## IntelliJ gotchas

Not a git repo yet. The module is registered in the workspace-root `../.idea/modules.xml`, while its
`EUVModCreatorBack.iml` sits in this folder; `.gitignore` excludes both `.idea/` and `*.iml`, so no IDE config is
version-controlled.

**The IntelliJ module name must equal the pom's `artifactId`.** IntelliJ syncs the two in *both* directions —
giving the module a friendlier name rewrote `<artifactId>` to it and broke the build with `'artifactId' with
value 'Back End' does not match a valid id pattern`. Module name, `.iml` filename, and `artifactId` are all
`EUVModCreatorBack`, and `../.idea/compiler.xml` references that same name for the Lombok annotation profile and
the `-parameters` javac flag. Rename in one place and all four must follow.

**Edit `../.idea/*.xml` only while IntelliJ is closed** — it holds the project model in memory and rewrites those
files on exit, silently discarding external edits.
