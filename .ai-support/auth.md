# Auth

How users register, log in and stay logged in, and why each piece is the way it is. The rules that are easy to
break by accident are repeated as one-liners in `CLAUDE.md`; this file is the reasoning behind them.

## Endpoints

| endpoint                  | status | returns                                                        |
| ------------------------- | ------ | -------------------------------------------------------------- |
| `POST /api/auth/register` | built  | 201 `{id, username}` — does not log in                         |
| `POST /api/auth/login`    | built  | 200 `{accessToken}` + `refresh_token` cookie, starts a session |
| `POST /api/auth/refresh`  | built  | 200 `{accessToken}` + rotated `refresh_token` cookie           |
| `POST /api/auth/logout`   | built  | 204 + cleared `refresh_token` cookie, ends the session         |
| `GET /api/users/me`       | built  | 200 the caller's profile — needs the access token              |

## JWT plus a server-side session row

Not stateless JWT, and not plain session cookies. A short-lived access token, and a rotatable refresh token stored
*hashed* in `user_sessions` — the stored row is what keeps revocation working, which a stateless JWT can't do
before it expires. The refresh token travels in an `HttpOnly` cookie, never `localStorage`, where any script on the
page could read it.

Tables: `users`, `user_auth` (1:1, the password hash), `user_sessions` (one row per login).

## Access tokens

Spring Security's OAuth2 resource server validates them, not a hand-written filter. The app signs its own tokens
(HS256, `NimbusJwtEncoder`, in `TokenService`) and Spring validates them; `SecurityConfig` holds the encoder and the
decoder. The subject (`sub`) is the user id, and `sid` the session's row id (see
[The current user](#the-current-user)).

Settings bind to `AuthProperties` (`auth.jwt.*`): `secret` is required — `JWT_SECRET` in production, never a default
in any committed file — `access-token-ttl` defaults to 15m and `refresh-token-ttl` to 30d, both in code.

The secret must be Base64 of at least 32 bytes, the smallest key HS256 allows. `AuthProperties` checks that in its
compact constructor, so a short or non-Base64 secret stops startup. Without the check the app boots anyway: Nimbus
checks the key length only when it creates its signer, inside the first `encode()`, so every login would answer 500.
A missing or blank secret is left to `@NotBlank`, which runs after the constructor. `AuthPropertiesTest` binds each
case through an `ApplicationContextRunner`, the way startup does.

Everything outside `/api/auth/**` needs a Bearer token; without a valid one the answer is 401
`auth.invalid_access_token`. The filter chain is stateless and CSRF protection is off, which is only safe while the
refresh cookie is `SameSite`: the browser then never attaches it to a request another site started.

## Refresh tokens

32 bytes from `SecureRandom` in URL-safe Base64 without padding: 43 characters, none of which a cookie parser could
mangle. `TokenService.newRefreshToken()` returns the token with its expiry as a `RefreshToken`, so the TTL never
leaves `security/`. Refresh passes the session's expiry to `newRefreshToken(Instant)` instead.

The session row stores the token's **SHA-256**, as hex, never the token itself, so a leaked table or backup holds
nothing a client could present. Not BCrypt as for passwords, for two reasons:

- BCrypt's slowness protects guessable input, and 256 random bits can't be guessed anyway.
- Its random salt makes the same token hash differently every time. Refresh has to find the session by hashing the
  cookie again and looking the result up through the unique index on `refresh_token_hash`.

`TokenServiceTest` pins the output to the published SHA-256 test vector.

The token only ever travels in the `refresh_token` cookie, never in a response body:

| attribute         | why                                                                                         |
| ----------------- | ------------------------------------------------------------------------------------------- |
| `HttpOnly`        | JavaScript can't read it, so an XSS bug can't steal it                                      |
| `Secure`          | HTTPS only                                                                                  |
| `SameSite=Strict` | never attached to a request another site started, which is what lets CSRF protection be off |
| `Path=/api/auth`  | sent to the auth endpoints only, not with every API call                                    |
| `Max-Age`         | computed from the session's `expires_at`, so the cookie and the row expire together         |

Every login starts its own session: each device holds its own token and can be logged out on its own. Refresh swaps
the token inside that session rather than starting another (see [Refresh](#refresh)).

`AuthService.login` returns an `AuthResult`, and `AuthController` splits it: the access token goes into the
`LoginResponse` body, the refresh token into `Set-Cookie`. Refresh returns the same pair. They are two records rather
than one with a `@JsonIgnore`d field, so the response type has no field a refresh token could leak through. `model/`
holds what a service hands its controller, which is never an HTTP body; `dto/` holds only HTTP bodies.

## Usernames

Unique ignoring case, stored with the casing they were registered with: `Andre` and `andre` are the same account,
and the user still sees `Andre`. A unique index on `lower(username)` enforces it.

Every lookup must filter on `lower(username)`, the index's exact expression, so the queries are hand-written
`@Query`s. Spring Data's derived `...IgnoreCase` compiles to `upper()`, which can't use the index — measured, see
[schema conventions](schema-conventions.md).

3–32 characters of `[A-Za-z0-9_]` (`RegisterRequest`).

## Passwords

8–64 characters, with no composition rules ("must contain a digit"), per NIST SP 800-63B-4: verifiers should allow at
least 64 characters, and composition rules push users towards predictable patterns without making passwords stronger.
The same revision asks for a 15-character minimum when the password is the only factor; see
[Open questions](#open-questions).

`@MaxBytes(72)` guards BCrypt's input limit, which `@Size` can't: `@Size` counts characters, and one character can
be up to 4 bytes in UTF-8. Spring Security 7 throws on a longer input instead of silently truncating it.

Hashes come from the delegating `PasswordEncoder`, prefixed `{bcrypt}`; see
[schema conventions](schema-conventions.md) for why the column has no length limit and there's no salt column.

## Register

201 with the new user, and no login: sessions and cookies are created by login only, so the frontend calls login
next. A taken username is 409 `auth.username_taken` — that necessarily reveals the name exists. Too many
registrations from one IP get 429 `rate_limited` first (see [Rate limiting](#rate-limiting)).

**BCrypt runs outside the transaction.** A transaction takes a pooled connection when it starts — Hibernate's `begin()`
calls `setAutoCommit(false)` on it — and holds it until commit. BCrypt takes about 80 ms and production's pool has 4
connections, so four registrations hashing inside transactions would stall every other request. `register` checks the
name and hashes first, then saves the user and the credentials in one `TransactionOperations.execute` callback: Boot's
`TransactionTemplate`, injected through its interface so a unit test can pass a mock. Not a `@Transactional` helper on
`AuthService`: a call from the same class skips the proxy, so the helper would run without a transaction.
`AuthServiceTest.registerHashesThePasswordBeforeTheTransactionStarts` pins the order. `login` has no `@Transactional`
for the same reason: BCrypt runs between two short repository transactions, and nothing there needs to be atomic.

**Two registrations at once** can both pass the existence check while they hash; the unique index on `lower(username)`
stops the second at its insert. `saveAndFlush` runs that insert inside the `try`, where the
`DataIntegrityViolationException` becomes the same 409 `auth.username_taken`. With `save` the insert waits for commit,
after the callback returns, and the answer was `GlobalExceptionHandler`'s generic 409 `conflict` —
`RegisterEndpointTest.sameUsernameRegisteredTwiceAtOnceIsUsernameTaken` failed 3 runs out of 3 that way. The existence
check stays anyway: it answers a taken name without spending a hash on it.

## Login

Success returns the access token and starts a session (see [Refresh tokens](#refresh-tokens)). Failure writes nothing
and sets no cookie. Too many attempts, from one IP or on one username, get 429 `rate_limited` before the password is
checked (see [Rate limiting](#rate-limiting)).

One 401 `auth.invalid_credentials` for both an unknown username and a wrong password. Two different answers would
tell an attacker which usernames exist, so they could spend their guesses only on real accounts. (Register's 409
reveals the same thing, but login is the endpoint attacked in volume, and it matters far more if login by email is
ever added — an email address is personal data.)

The same must hold for **timing**. BCrypt is deliberately slow, so an unknown username that fails without hashing
answers measurably faster than a wrong password. An unknown username therefore still runs `matches()`, against a
dummy hash `AuthService` encodes at startup (so it always has the algorithm and cost of real hashes). Never let a
code path skip `matches()`: an `orElseThrow` before it, or a `||` that short-circuits it, brings the leak back.
`AuthServiceTest.loginStillRunsBcryptForUnknownUser` catches that.

Measured on the dev machine after the fix, averaging 10 failed logins each: wrong password 81.9 ms, unknown user
75.4 ms — within noise.

`LoginRequest` only checks `@NotBlank` and `@MaxBytes(72)`, not register's rules. Those describe *new* accounts;
tightening them must not lock out existing ones. A username that breaks them just isn't found.

## Refresh

Reads the `refresh_token` cookie, hashes it, finds the session, and answers with a new access token and a new cookie.

**Rotation in place.** The new token's hash replaces the old one on the same row, so the old token stops working at
once. Not a new row per refresh, which would add a row every access-token lifetime per active user. In place, a row
stays "one login on one device": logout revokes one row, and `updated_at` is the last refresh. The cost is reuse
detection: the old hash is gone, so a replayed old token looks like any unknown token. If that is ever wanted, a new
migration can add a `previous_token_hash` column.

**Fixed lifetime.** A session ends `refresh-token-ttl` after login, however active the user is. Refresh never extends
it: the rotated token takes the session's own `expires_at` (`TokenService.newRefreshToken(Instant)`), so the cookie's
`Max-Age` shrinks with every refresh and still runs out together with the row. An access token issued just before the
end outlives the session by up to `access-token-ttl`. Logout has the same gap, and it is accepted; capping the
access token's expiry at the session's would close it.

**One 401 `auth.invalid_refresh_token`** for a missing cookie, an unknown token, and a revoked or expired session:

- The user never sees refresh fail. The frontend calls it in the background and reacts to every failure the same
  way, by sending the user to log in.
- "Expired" hardly ever arrives: the cookie's `Max-Age` ends when the session does, so the browser has dropped it.
- "Revoked" would confirm to someone replaying a stolen token that it was real.

`@CookieValue(required = false)` is what makes a missing cookie this 401. Without it, Spring answers 400 with
`MissingRequestCookieException` before the service runs. If reuse detection is added, a separate "logged out for
security reasons" code would be the one worth having.

**A row lock against concurrent refreshes.** `findByRefreshTokenHash` has `@Lock(PESSIMISTIC_WRITE)`, which locks
the row until the transaction commits. When two requests present the same token at once, the second waits, then finds
the hash already replaced and gets the 401. Without the lock both succeed, the token has been used twice, and the
browser may keep whichever cookie arrived last, possibly a dead one.
`RefreshEndpointTest.sameTokenRefreshedTwiceAtOnceWorksOnlyOnce` failed 3 runs out of 3 with the lock removed.

**`@Transactional` does three jobs here:**

- It holds that lock.
- It lets dirty checking write the new hash without a `save()`.
- It rolls back on any `RuntimeException`, which includes `ApiException`. Anything written before a `throw` inside the
  method never reaches the database, so a failed refresh can't revoke or change the session.

## Logout

Revokes the session the cookie belongs to (sets `revoked_at`) and clears the cookie: 204, no body.

**It never fails.** A missing cookie, an unknown token, and a session already revoked or expired all get the same 204
and the cleared cookie. The user asked to be logged out and afterwards is; an error would hand the frontend a case
with nothing to do about it. A second logout keeps the first `revoked_at`.

**No access token.** `/api/auth/**` is open and the cookie identifies the session, so logout still works once the
access token has expired. `SameSite=Strict` keeps other sites from sending the cookie, so none of them can log the
user out.

**Clearing a cookie means overwriting it.** HTTP has no delete: the response sets the same cookie, empty, with
`Max-Age=0`, and the browser drops it. The browser matches cookies on name, domain and path, so a clearing cookie with
another path would sit next to the real one and change nothing. `AuthController.refreshTokenCookie(value, maxAge)`
builds both, so they can't drift apart; `LogoutEndpointTest` checks every attribute.

**One device only.** The user's other sessions keep working. "Log out everywhere" would revoke all of the user's rows,
which needs to know who the user is — the access token — and isn't built.

**Access tokens outlive it** by up to `access-token-ttl`; the frontend throws its copy away. The same accepted gap as
at the end of a session (see [Refresh](#refresh)).

## The current user

A protected request knows who is calling and from which session without touching the database:

1. `BearerTokenAuthenticationFilter` has the `JwtDecoder` check the signature and `exp`.
2. `AuthenticatedUserConverter` turns the `Jwt` into an `AuthenticatedUserToken`, whose principal is
   `AuthenticatedUser(userId, sessionId)`, read from `sub` and `sid`.
3. The filter stores it in `SecurityContextHolder`, which belongs to the request's thread.
4. A controller parameter annotated `@CurrentUser AuthenticatedUser` receives it. `@CurrentUser` is a meta-annotation
   for Spring Security's `@AuthenticationPrincipal`. It needs `RetentionPolicy.RUNTIME`: without it Spring can't see
   the annotation and silently binds an empty record instead.

**The `sid` claim** is the session's row id. Login saves the session before it issues the token
(`GenerationType.UUID` assigns the id in Java during `save()`), and refresh reuses the session it found. Nothing reads
it beyond the principal yet; "log out everywhere else" and a per-request session check will.

**The converter never touches the database.** It runs on every authenticated request, most of which only need the
ids, and it runs in a filter, where an exception never reaches `GlobalExceptionHandler`. It throws
`InvalidBearerTokenException` for a token without a `sub` or `sid`, or with one that isn't a UUID: the filter turns an
`AuthenticationException` into a 401 and lets anything else escape as a 500. `UUID.fromString`'s
`IllegalArgumentException` did exactly that before it was wrapped
(`AccessTokenTest.tokenWhoseSubjectIsNotAUuidIsInvalidAccessToken`). Only a token signed with the app's key gets that
far, so it takes a leaked key or an issuing bug.

**Services take the ids as parameters,** not from `SecurityContextHolder`. The dependency shows in the signature, a
unit test just passes a UUID, and `@Async` or `@Scheduled` code, which runs on another thread with no request, can
call the same method.

**The 401 is Problem Details like every other error.** A missing, malformed, expired, forged or `sid`-less token gets
401 `auth.invalid_access_token` with `WWW-Authenticate: Bearer`. That happens in the filter chain, before
`DispatcherServlet`, so `@RestControllerAdvice` never sees it. `AccessTokenEntryPoint` hands an
`InvalidAccessTokenException` to the MVC `HandlerExceptionResolver` bean — the component `DispatcherServlet` itself
uses — so the body comes from `GlobalExceptionHandler`. It asks for the bean by
`@Qualifier("handlerExceptionResolver")`, since Boot's `DefaultErrorAttributes` implements the same interface, in a
hand-written constructor: Lombok's `@RequiredArgsConstructor` doesn't copy `@Qualifier` onto the parameter.
`AccessTokenTest` covers each kind of bad token.

**Revocation waits for `exp`.** Access tokens are checked by signature and `exp` alone; the session row is only read
on refresh. To make logout take effect at once, add an `OAuth2TokenValidator<Jwt>` to the decoder that looks the
session up by `sid`. `setJwtValidator` *replaces* the default validators, so wrap it together with
`JwtValidators.createDefault()` in a `DelegatingOAuth2TokenValidator`, or expired tokens start being accepted. It
costs a query per request; see [Caching](#caching).

## Me

`GET /api/users/me` returns the caller's profile: `id`, `username`, `displayName`, `bio`, `steamUrl`,
`paradoxForumUrl` and `discordUrl`. The username comes back as registered, whatever casing login used.

**Profile fields.** All optional, and `null` until the user sets them. Their rules will live only on the update
endpoint's request DTO, not in the table (see [schema conventions](schema-conventions.md)): `displayName` 1–50
characters, `bio` 1–500, each URL up to 200. The endpoint must store a blank value as `null`, so that `null` is the
only way to be empty. The frontend shows the username in place of a missing display name. Display names aren't unique, so show the username
beside one wherever impersonation would matter.

**One field per site, not a list of links.** The frontend knows which icon each one gets, and each can be checked
against its own site. Nothing sets these fields yet. When the update endpoint is built, each URL must be `https` on
its own host: `steamcommunity.com`, `forum.paradoxplaza.com`, and `discord.gg` or `discord.com` for Discord, which
takes a server invite since a Discord username isn't a link. The host check also keeps out `javascript:` URLs, which
run script when the frontend renders them as `href`s. Check the host, not the path: Steam alone has `/id/…` and
`/profiles/…`.
It lives in `auth/` (`UserController`, `UserService`) because `auth` owns `User`; a separate `user/` feature would
have the two packages importing each other.

`UserService.findUser` returns an `Optional<UserSummary>` and leaves "not found" to the caller. For `/me` it means the
token names a deleted user, since a token outlives its row by up to `access-token-ttl`. The answer is the same 401
`auth.invalid_access_token`, not a 404: the frontend refreshes, refresh fails because the sessions were deleted with
the user, and the user lands on the login page. A public profile would turn the same empty `Optional` into a 404.

## Caching

None yet: `findUser` is a primary-key lookup, and `/me` runs once per page load. The seam is in place instead:
`UserService.findUser` returns an immutable record, never the `User` entity. A cached entity would be shared across
threads, could hold lazy proxies, and Redis would have to serialize it.

When a measurement asks for a cache, Spring's cache abstraction adds one without changing the callers:

- `@EnableCaching` once, `@Cacheable("users")` on `findUser`, and `@CacheEvict` on every method that changes a user.
  Eviction is the part that goes wrong.
- The provider comes from the classpath: Caffeine (`spring-boot-starter-cache` + `caffeine`, which the pom already
  has for rate limiting; `spring.cache.caffeine.spec`) for one instance, Redis (`spring-boot-starter-data-redis`,
  `spring.cache.redis.time-to-live`) for several. With neither, Boot falls back to an unbounded `ConcurrentHashMap`
  with no expiry — never in production.
- `@Cacheable` works through a proxy, like `@Transactional`: a call from another method of the same class skips it.
- Caffeine lives in one JVM, so an eviction on one instance doesn't reach another. Boot's Redis cache uses JDK
  serialization, so cached records must implement `Serializable`.
- Set `spring.cache.type=none` in `application-test.properties`: `IntegrationTest` empties the tables before each
  test, not the cache.

The first real candidate is the per-request session check above, not `/me`, because it would run on every request.

## Rate limiting

Three limits, in front of the two endpoints that run BCrypt:

| limit          | key                | default                      | enforced by                                   |
| -------------- | ------------------ | ---------------------------- | --------------------------------------------- |
| login requests | client IP          | 10 per minute                | `RateLimiter`, through `RateLimitInterceptor` |
| registrations  | client IP          | 5 per hour                   | `RateLimiter`, through `RateLimitInterceptor` |
| failed logins  | submitted username | 5, then locked for 5 minutes | `Lockout`, in `AuthService.login`             |

The numbers are `auth.rate-limit.*` settings in `RateLimitProperties`, with these defaults in code.

Every rejection is 429 `rate_limited`, with `params.retryAfterSeconds` and the same number in a `Retry-After` header.
`ApiException` carries no headers, so `RateLimitException` has its own handler in `GlobalExceptionHandler`; Spring
picks the handler for the closest exception type, so it wins over the `ApiException` one. `RateLimitConfig` in
`auth/security/` builds the limiters from `RateLimitProperties` and registers the interceptors. `ratelimit/` holds
only the generic pieces, so another feature can declare its own limits the same way.

**Per IP: a token bucket, in an interceptor.** Bucket4j with greedy refill: capacity is the burst, and tokens come back
one at a time (one every 6 seconds at 10 per minute). It throttles instead of locking because an IP is shared — a
school, a LAN party, a mobile carrier's NAT — so it only has to stop one machine hammering. A `HandlerInterceptor`, not
a servlet filter: an exception from `preHandle` goes through the MVC exception resolvers into `GlobalExceptionHandler`,
one from a filter doesn't. `preHandle` runs before the body is parsed, so malformed requests cost a token too. CORS
preflights never reach it: Spring Security's CORS filter answers them. The path patterns are raw URLs, `/api` included.

**Per username: a lockout, in the service.** 5 failed logins within 5 minutes lock the username for 5 minutes from the
5th; a successful login clears the count. It sits in `AuthService` because only there is the username parsed and the
outcome known.

- Keyed on the username as submitted, lowercased with `Locale.ROOT`, never the user id. Keyed on the id, only real
  accounts could be locked, and a 429 would confirm the name exists — the enumeration the dummy hash closes for timing.
- Checked before BCrypt: a locked username costs no hashing, and a correct password can't get through the lock.
- Counted up front and forgiven on success, not checked first and counted after a failure. `Lockout.consume` reads
  and increments the count in one `asMap().compute`, which Caffeine runs atomically per key. With a check and a
  separate count, concurrent guesses could all pass the check before any failure was recorded.
- The lock starts at the 5th attempt; attempts rejected during it don't extend it.

The trade-off: anyone can lock an account for 5 minutes by failing with its username. The lock is short, and the
per-IP limit caps how many accounts one machine can keep locked. NIST SP 800-63B-4 also caps consecutive failures on
an account at 100, after which the password is disabled; that needs a way back in (password reset), so it waits for
the email decision.

Measured against the running app: five wrong passwords for `GhostUser` got 401, the 6th got 429 with
`Retry-After: 300`, and `ghostuser` was locked too. The 11th login from one IP within a minute got 429 with
`Retry-After: 4`.

**Storage: Caffeine, in each instance.** Keys come from the client, so every cache has a `maximumSize`: a plain map
would grow with each rotated IP or username until the heap ran out. Expiry never drops state that still matters — a
bucket expires after `period` idle, when it is full again, and a lockout entry `lockDuration` after its last write,
never before its `resetAt`. A restart resets every limit, which is harmless.

With several instances, each would keep its own counts and multiply the limits. Bucket4j's `ProxyManager` moves
buckets into shared storage; PostgreSQL (`bucket4j_jdk17-postgresql`) fits, since one round trip is nothing next to
BCrypt, but it has no TTL, so a scheduled job must call `removeExpired`. `Lockout` isn't a bucket and would need its
own table. Callers only see `consume`, `clear` and `reset`, so the change stays inside `ratelimit/`.

**Not built yet:**

- A global cap on concurrent BCrypt: `@ConcurrencyLimit(policy = REJECT)` (Spring Framework 7) on `login` and
  `register`, mapped to 503. Per-key limits don't stop a flood from many IPs.
- IPv6 keys on the /64 prefix: one connection usually owns a whole /64, so a full address is free to rotate.

**Tests.** `IntegrationTest` resets every `RateLimiter` and `Lockout` before each test: all tests come from 127.0.0.1
and share one app, so the counts would carry over and turn later tests into 429s (35 did before the reset).
`Lockout` reads the time from a `Clock`, so `LockoutTest` moves time instead of sleeping through a 5-minute lock.

- `LockoutTest`, `RateLimiterTest`: the lock window, its start at the last allowed attempt, rejected attempts not
  extending it, `Retry-After` rounding up, `clear` and `reset`.
- `AuthServiceTest`: the lockout runs before BCrypt, on the lowercased username, and only a success clears it.
- `LoginEndpointTest`, `RegisterEndpointTest`: every limit over HTTP, including the correct password refused while
  locked, a different casing locked too, an unknown username locked like a real one, and invalid requests using up
  the per-IP limit.
- `RateLimitPropertiesTest`: the defaults, and a zero capacity or duration stopping startup.
- `GlobalExceptionHandlerTest`: the 429 body and `Retry-After`.

Each was checked against a broken version: without the `clear`, without the lowercasing, with a lock that rejected
attempts extend, and without `@Validated`, the matching tests failed.

## The frontend's side

Not built yet. What the backend expects of it:

- The access token lives in memory only, never `localStorage`. A page load has none, so the app starts with a
  refresh: a 200 means still logged in, then `/me`; a 401 means the login page.
- One fetch wrapper sends every request. It adds `Authorization: Bearer`, and on a 401 `auth.invalid_access_token`
  from outside `/api/auth/` it refreshes once and retries once. `auth.invalid_refresh_token` means the session is over.
- Parallel requests share one refresh. If each started its own, they would all send the same cookie; rotation lets
  only the first through (`RefreshEndpointTest.sameTokenRefreshedTwiceAtOnceWorksOnlyOnce`), and the rest would log
  the user out.
- The Vite dev server on `localhost:5173` is the same site as the API on `localhost:8080` but a different origin, so
  fetch needs `credentials: 'include'`. The backend half is built: `WebConfig` allows the origins in
  `web.cors.allowed-origins` with credentials. Without both, the browser ignores the cookie. Check then that the
  `Secure` cookie survives plain `http://localhost` in the browsers used for development.
- A 429 `rate_limited` is not a logout: show "try again in `retryAfterSeconds`" and keep the session.

## Where password hashes are read

`user_auth` is split from `users` so ordinary user queries never carry a hash. Exactly one query reads it:
`UserAuthRepository.findLoginCredentials`, a JPQL join of `User` and `UserAuth` (an entity join with an explicit
`on`, since `UserAuth.userId` is a plain column, not a mapped relation) into the `LoginCredentials` record.

Don't add a `@OneToOne` from `User` to `UserAuth`. Hibernate can't lazy-load the side of a one-to-one that doesn't
hold the foreign key (without bytecode enhancement), so every `User` load would pull the hash back in.

## Open questions

- **Discord OAuth2 vs username + password.** Not settled.
- **No email column** on `users`, so password reset is impossible until the above is decided.
- **Minimum password length.** NIST SP 800-63B-4 asks for 15 characters when the password is the only factor (8 only
  alongside a second one); register allows 8. Raising it affects only new passwords, since login doesn't apply
  register's rules.
- **Before going public:**
  - Behind a reverse proxy, set `server.forward-headers-strategy=native` (and `server.tomcat.remoteip.internal-proxies`
    if the proxy's address isn't private). Otherwise `getRemoteAddr()` is the proxy, and every client shares one
    rate-limit bucket. Never read `X-Forwarded-For` by hand: the client writes it, so a fake one per request gets a
    fresh bucket each time.
  - XSS discipline and a Content-Security-Policy in the frontend. `HttpOnly` stops a script stealing the refresh
    cookie, not using it: injected code can call `/refresh` itself while the tab is open.
  - HTTPS everywhere, and the frontend and the API on the same site (`app.example.com`, `api.example.com`). The
    browser never sends a `SameSite=Strict` cookie across sites, and `SameSite=None` would bring CSRF back. Set
    `CORS_ALLOWED_ORIGINS` to the frontend's origin, or empty if the API serves the frontend itself.
  - A `JWT_SECRET` of 32 random bytes: whoever holds it can sign a token for any user. Changing it only invalidates
    access tokens, and clients refresh silently.
- **Later:** "log out everywhere", a cleanup job for expired and revoked sessions, a Have I Been Pwned check on new
  passwords, the per-request session check (see [The current user](#the-current-user)), reuse detection
  (`previous_token_hash`, see [Refresh](#refresh)).
