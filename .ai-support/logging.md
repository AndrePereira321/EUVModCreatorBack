# Logging

What the backend logs, at which level, and what never goes into a log line. `CLAUDE.md` repeats only the rules
any feature can break; the rest lives here. The auth events themselves are listed in [auth](auth.md#logging).

## Setup

SLF4J through Lombok's `@Slf4j`, which writes `private static final Logger log = LoggerFactory.getLogger(...)` into
the class at compile time. Logback does the writing, configured by Boot from `logging.*` properties, not from a
`logback-spring.xml` (see [Why properties, not XML](#why-properties-not-xml)).

| profile      | level                                             | writes to                                |
| ------------ | ------------------------------------------------- | ---------------------------------------- |
| `local`      | `com.euvmodcreator` at DEBUG, the rest at INFO    | console and `logs/euvmodcreator.log`     |
| `test`       | INFO; `AuthLoggingTest` turns DEBUG on for itself | console                                  |
| `production` | INFO                                              | console and the file named by `LOG_FILE` |

`spring.mvc.log-resolved-exception=false` sits in the base file. Devtools, which is on for local runs, sets it to
`true` by default, and Spring MVC then logs every handled exception a second time at WARN (`Resolved [...]`). That
made each 401 a WARN, next to the DEBUG line `GlobalExceptionHandler` already writes.

## Log files

Setting `logging.file.name` makes Boot add a `RollingFileAppender` next to the console one. The rotation settings
are in `application.properties`, shared by every profile that names a file:

| setting                                        | value   | effect                                                            |
| ---------------------------------------------- | ------- | ----------------------------------------------------------------- |
| file name pattern (Boot's default)             | daily   | at midnight the file becomes `euvmodcreator.log.2026-09-27.0.gz`  |
| `logging.logback.rollingpolicy.max-file-size`  | `100MB` | a bigger day splits into `.0.gz`, `.1.gz`…                        |
| `logging.logback.rollingpolicy.max-history`    | `15`    | days of archives kept, however many parts each day has            |
| `logging.logback.rollingpolicy.total-size-cap` | `2GB`   | oldest archives go first past it, so a flood can't fill the disk  |

- **Local** writes to `logs/`, relative to the working directory: the module folder, from IntelliJ and from
  `./mvnw`. `.gitignore` excludes `/logs/`.
- **Production** takes the path from `LOG_FILE`, with no fallback, like every other production setting. Without it
  startup stops with `Could not resolve placeholder 'LOG_FILE'`; checked by starting the production profile without
  it.
- **Tests** name no file, so they write none.
- 15 days is also the retention for the IPs the logs hold (see [What a line contains](#what-a-line-contains)).

## Why properties, not XML

The properties cover every setting above. A `logback-spring.xml` earns its place once something needs more: a second
file for some loggers (an auth audit log with its own retention), an async or syslog appender, or filters routing
lines by level or MDC value.

- Name it `logback-spring.xml`, never `logback.xml`. Logback reads `logback.xml` on its own, before Spring starts, so
  it sees no Spring property and no `<springProfile>`.
- It replaces Boot's setup rather than adding to it. It has to include Boot's `defaults.xml` and appenders itself,
  and the `logging.*` properties then work only where the XML references them.

## Request id and client IP

Every line written while a request is handled carries both, after the thread name:

```
2026-09-27T21:34:00.962+02:00  INFO 20544 --- [EUVModCreatorBack] [cat-handler-195] [44ba7195a6a643b1 127.0.0.1] com.euvmodcreator.auth.AuthService       : User 5f0c… logged in, session 9a41…
```

`MdcFilter` (`logging/`) puts them into the **MDC**, the Mapped Diagnostic Context: a per-thread map that Logback
reads into every line written on that thread. `logging.pattern.correlation` prints them; it is the slot Boot's
default pattern keeps for request correlation, which Micrometer Tracing would otherwise fill. The `%replace` drops
the empty brackets from lines written outside a request, such as startup and the cleanup job. The id also goes back
in the `X-Request-Id` response header, so a request seen in the browser's network tab can be found in the log.

- **Why the MDC and not a parameter.** Services take ids, not HTTP types (see
  [auth](auth.md#the-current-user)). The MDC lets `AuthService` log a failed login with the client's IP without
  `HttpServletRequest` in its signature.
- **It runs first** (`Ordered.HIGHEST_PRECEDENCE`), before Spring Security's filter chain, whose entry point logs
  every rejected access token. `AuthLoggingTest.securityFilterLinesCarryTheRequestIdToo` failed with the `@Order`
  removed: a filter bean without one runs last.
- **The id is generated, never read from a request header.** A client-chosen id could repeat another request's on
  purpose, or carry a line break into the log. A reverse proxy that assigns its own ids would be the reason to
  revisit this.
- **The IP is `getRemoteAddr()`**, the value the rate limiter keys on, with the same caveat behind a proxy (see
  [auth](auth.md#open-questions)).
- **The MDC belongs to a thread.** Work handed to another thread (`@Async`, an executor) starts without it. Each
  request runs on its own virtual thread, so nothing carries over today; the filter still removes its keys in a
  `finally`, because a pooled platform thread would carry them into the next request (`MdcFilterTest`).

## Levels

| level | when                                                                                       | examples                                                                |
| ----- | ------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------- |
| ERROR | a request or a job failed, and someone has to act: a bug, the database down, a leaked key | unhandled exception (500); an access token signed with our key but broken |
| WARN  | handled, but worth a look if it repeats                                                    | a username locked; 503 `server_busy`; a constraint no check caught      |
| INFO  | events worth keeping in production                                                         | register, login (both outcomes), logout, a job's result, startup config |
| DEBUG | detail for development                                                                     | every error response, refused refreshes, why an access token failed    |
| TRACE | unused                                                                                     |                                                                         |

**The flood rule.** An event a client can trigger at will logs at INFO or above only if something caps its rate.
Otherwise one client could fill the disk and bury the lines that matter.

- Failed logins are INFO: the per-IP limit and the concurrency limit cap them.
- A username lock is WARN: at most once per lock duration per username.
- 429s are DEBUG: nothing caps rejected requests, that is what rejecting them means.
- Refused refreshes are DEBUG: refresh has no rate limit. One of them is a replayed old token, the one refusal
  worth INFO; if reuse detection is built (see [auth](auth.md#refresh)), the detection gets the WARN.
- 503 `server_busy` is WARN. Many IPs can trigger it, but only past every per-IP limit, and then it is the line to
  see.

## What a line contains

**Users and sessions by their UUID:** `User {} logged in, session {}`. The database maps an id to a username.

**Never:**

- Passwords, wrong ones included; password hashes.
- Access tokens, refresh tokens, refresh-token hashes, the JWT secret, `Authorization` and `Cookie` headers.
- **Anything the client typed:** the username at login, header values, request bodies. A username field sometimes
  holds the password, typed there by mistake. And a string with a line break in it writes a fake log line.
  UUIDs the app generated, the socket's IP and the generated request id carry neither risk.
- A whole object (`log.debug("{}", request)`): its `toString` decides what shows.

**Records holding a secret override `toString` to hide it:** `LoginRequest`, `RegisterRequest`, `LoginResponse`,
`AuthResult`, `RefreshToken`, `LoginCredentials`, each with a test. It guards more than our own lines: at DEBUG,
Spring MVC logs every request body it reads through `toString`
(`AbstractMessageConverterMethodArgumentResolver`), so `logging.level.org.springframework.web=debug` would print
every password. A new record with a secret in it gets the same override.

Logs hold IPs and user ids, which are personal data under the GDPR, so production keeps them for a bounded time (see
[Open questions](#open-questions)).

## Message style

- `{}` placeholders, never concatenation. SLF4J builds the string only when the level is on.
- An exception goes last, without a `{}` of its own. SLF4J then prints its stack trace.
- English, sentence case, no full stop. Name the thing before its id: `session {}`.
- Events in the past tense (`User {} registered`); refusals as `Refresh refused: <reason>`.

## Where the log call goes

- **The service logs the domain event.** It knows the outcome and the ids. Controllers translate HTTP and don't log.
- **`GlobalExceptionHandler` logs every error response once:** an `ApiException` at DEBUG (status and code), 503 at
  WARN, `DataIntegrityViolationException` at WARN with its stack trace, anything unhandled at ERROR with it. A
  service that throws an `ApiException` doesn't log it again, unless it adds what the handler can't know: a failed
  login logs whose it was.
- **Log or rethrow, not both.** A stack trace logged at every layer it passes appears several times for one failure.
  A `catch` that turns one exception into another logs the original at DEBUG, with the exception, so the cause isn't
  lost: `AuthService` does this when the unique index refuses a registration.
- **A scheduled job logs its result at INFO**, and needs no `catch` for its failures: Spring's scheduler logs an
  exception at ERROR (`Unexpected error occurred in scheduled task`) and keeps the schedule.

## Tests

- `AuthLoggingTest` attaches a Logback `ListAppender` to the root logger, turns DEBUG on, and runs register, failed
  logins (one with the password in the username field), login, `/me`, refresh, a replayed refresh and logout. No
  message, MDC value or stack trace may contain the password, a token or a token hash. It failed with one
  `log.debug` of the password added.
- The same class checks the request id and IP on service and security-filter lines, the single WARN when a username
  locks, and an unknown username logged without its name.
- `MdcFilterTest`: the MDC during the request, a new id per request, and nothing left behind when the request throws.

## Open questions

- **Files or console only in production.** Decide with the server. On a machine running the jar, the file is the
  record, and `logging.console.enabled=false` would stop everything being stored twice if something also keeps the
  console output. In a container, the platform collects the console, and a file inside the container disappears
  with it unless it sits on a volume.
- **Format.** Plain text while a person reads the logs. Once an aggregator reads them, Boot's structured logging
  (`logging.structured.format.console=ecs` or `logstash`) writes one JSON object per line, with every MDC key as a
  field.
- **An access log**, one line per request with method, path, status and duration. Tomcat's
  (`server.tomcat.accesslog.enabled`) writes its own file, outside Logback and without the MDC. Not on yet.
- **Hibernate logs a violated constraint itself** (`org.hibernate.orm.jdbc.error`, WARN) before the exception
  reaches our code, so the registration race shows up twice.
