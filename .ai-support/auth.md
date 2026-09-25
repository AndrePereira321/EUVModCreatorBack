# Auth

How users register, log in and stay logged in, and why each piece is the way it is. The rules that are easy to
break by accident are repeated as one-liners in `CLAUDE.md`; this file is the reasoning behind them.

## Endpoints

| endpoint                  | status  | returns                                                        |
| ------------------------- | ------- | -------------------------------------------------------------- |
| `POST /api/auth/register` | built   | 201 `{id, username}` — does not log in                         |
| `POST /api/auth/login`    | built   | 200 `{accessToken}` + `refresh_token` cookie, starts a session |
| `POST /api/auth/refresh`  | built   | 200 `{accessToken}` + rotated `refresh_token` cookie           |
| `POST /api/auth/logout`   | planned | revokes the session, clears the cookie                         |

## JWT plus a server-side session row

Not stateless JWT, and not plain session cookies. A short-lived access token, and a rotatable refresh token stored
*hashed* in `user_sessions` — the stored row is what keeps revocation working, which a stateless JWT can't do
before it expires. The refresh token travels in an `HttpOnly` cookie, never `localStorage`, where any script on the
page could read it.

Tables: `users`, `user_auth` (1:1, the password hash), `user_sessions` (one row per login).

## Access tokens

Spring Security's OAuth2 resource server validates them, not a hand-written filter. The app signs its own tokens
(HS256, `NimbusJwtEncoder`, in `TokenService`) and Spring validates them; `SecurityConfig` holds the encoder and the
decoder. The subject (`sub`) is the user id.

Settings bind to `AuthProperties` (`auth.jwt.*`): `secret` is required — `JWT_SECRET` in production, never a default
in any committed file — `access-token-ttl` defaults to 15m and `refresh-token-ttl` to 30d, both in code.

Everything outside `/api/auth/**` needs a Bearer token. The filter chain is stateless and CSRF protection is off,
which is only safe while the refresh cookie is `SameSite`: the browser then never attaches it to a request another
site started.

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

`AuthService.login` returns a `LoginResult`, and `AuthController` splits it: the access token goes into the
`LoginResponse` body, the refresh token into `Set-Cookie`. Refresh returns the same pair. They are two records rather than one with a `@JsonIgnore`d
field, so the response type has no field a refresh token could leak through. `result/` holds what a service hands its
controller, which is never serialized; `dto/` holds only HTTP bodies.

**Not built yet: the frontend side.** The Vite dev server on `localhost:5173` is the same site as the API on
`localhost:8080` but a different origin, so:

- fetch needs `credentials: 'include'`;
- CORS needs `allowCredentials(true)` with an explicit origin.

Without both, the browser ignores the cookie. Also check then that the `Secure` cookie survives plain
`http://localhost` in the browsers used for development.

## Usernames

Unique ignoring case, stored with the casing they were registered with: `Andre` and `andre` are the same account,
and the user still sees `Andre`. A unique index on `lower(username)` enforces it.

Every lookup must filter on `lower(username)`, the index's exact expression, so the queries are hand-written
`@Query`s. Spring Data's derived `...IgnoreCase` compiles to `upper()`, which can't use the index — measured, see
[schema conventions](schema-conventions.md).

3–32 characters of `[A-Za-z0-9_]` (`RegisterRequest`).

## Passwords

8–32 characters, with no composition rules ("must contain a digit"), per current NIST guidance: such rules push
users towards predictable patterns without making passwords stronger.

`@MaxBytes(72)` guards BCrypt's input limit, which `@Size` can't: `@Size` counts characters, and one character can
be up to 4 bytes in UTF-8. Spring Security 7 throws on a longer input instead of silently truncating it.

Hashes come from the delegating `PasswordEncoder`, prefixed `{bcrypt}`; see
[schema conventions](schema-conventions.md) for why the column has no length limit and there's no salt column.

## Register

201 with the new user, and no login: sessions and cookies are created by login only, so the frontend calls login
next. A taken username is 409 `auth.username_taken` — that necessarily reveals the name exists.

## Login

Success returns the access token and starts a session (see [Refresh tokens](#refresh-tokens)). Failure writes nothing
and sets no cookie.

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

## Where password hashes are read

`user_auth` is split from `users` so ordinary user queries never carry a hash. Exactly one query reads it:
`UserAuthRepository.findLoginCredentials`, a JPQL join of `User` and `UserAuth` (an entity join with an explicit
`on`, since `UserAuth.userId` is a plain column, not a mapped relation) into the `LoginCredentials` record.

Don't add a `@OneToOne` from `User` to `UserAuth`. Hibernate can't lazy-load the side of a one-to-one that doesn't
hold the foreign key (without bytecode enhancement), so every `User` load would pull the hash back in.

## Open questions

- **Discord OAuth2 vs username + password.** Not settled.
- **No email column** on `users`, so password reset is impossible until the above is decided.
- **Later:** rate limiting on login, a cleanup job for expired sessions, a Have I Been Pwned check on new passwords.
