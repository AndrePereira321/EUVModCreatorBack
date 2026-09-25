# Auth

How users register, log in and stay logged in, and why each piece is the way it is. The rules that are easy to
break by accident are repeated as one-liners in `CLAUDE.md`; this file is the reasoning behind them.

## Endpoints

| endpoint                  | status        | returns                                              |
| ------------------------- | ------------- | ---------------------------------------------------- |
| `POST /api/auth/register` | built         | 201 `{id, username}` — does not log in               |
| `POST /api/auth/login`    | built         | 200 `{accessToken}` — no session or cookie yet       |
| `POST /api/auth/refresh`  | planned       | rotates the refresh token, new access token          |
| `POST /api/auth/logout`   | planned       | revokes the session, clears the cookie               |

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

Settings bind to `JwtProperties` (`app.jwt.*`): `secret` is required — `JWT_SECRET` in production, never a default
in any committed file — and `access-token-ttl` defaults to 15m in code.

Everything outside `/api/auth/**` needs a Bearer token. The filter chain is stateless and CSRF protection is off,
which is only safe while the refresh cookie is `SameSite`: the browser then never attaches it to a request another
site started.

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
