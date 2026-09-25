# Schema conventions

Rules for writing Flyway migrations. The figures were measured on PostgreSQL 17.5 with Spring Security 7.1.0 —
re-run them if you doubt one, don't relax a rule from memory.

## `text`, never `varchar(n)` or `char(n)`

In PostgreSQL all three are the same storage. `varchar(n)` is `text` plus a length check, and `char(n)` blank-pads
to full width — `'andre'` costs 68 bytes as `char(64)` against 9 as `text`. Nothing is pre-allocated, so a length
limit never saves space. (It does in MySQL, SQL Server and Oracle, which is where the habit comes from.)

Length rules go in a `check` constraint, which can also express a minimum and a pattern:

```sql
username text not null unique check (char_length(username) between 3 and 32),
```

## `timestamptz`, never `timestamp`

`timestamp` carries no offset, and `now()` returns a `timestamptz` — so a `timestamp` column silently discards the
zone. No error, just wrong data the first time server and user aren't in the same one. Maps to `Instant` in Java.

`default now()` fires on insert only. It does not touch `updated_at` on an update.

## `uuid primary key default gen_random_uuid()`

Core since PostgreSQL 13, no extension needed. UUID over `bigserial` because ids end up in URLs and API
responses, where sequential integers leak the row count and invite range-walking.

## Index foreign key columns explicitly

PostgreSQL auto-creates an index only where the index *is* the enforcement mechanism — primary keys and unique
constraints. Foreign key enforcement borrows the parent's index, so the referencing side gets nothing.

Without one, `on delete cascade` sequential-scans the child: **15.4 ms against 0.155 ms** at 200k rows. A `unique`
constraint on the column already provides the index — don't add a second.

## B-tree, not hash indexes

Hash indexes only win on large keys (64-char text: 6.5 MB against 18 MB) and lose on small ones (uuid: 6512 kB
against 6184 kB). They also support only `=`, only one column, and **cannot back a `unique` constraint** — which
our token columns need, so the decision is made for us.

## Case-insensitive uniqueness: a unique index on `lower(column)`

`unique` on a `text` column is case-sensitive, so `Andre` and `andre` both fit. A unique *expression* index,
`create unique index ... on users (lower(username))`, rejects the second while the column keeps the casing the
user typed. It replaces the plain `unique` constraint, which it makes redundant — drop that one.

**The query must use the exact same expression**, or PostgreSQL can't use the index. Spring Data's derived
`...IgnoreCase` methods always compile to `upper()` (Spring Data JPA 4.1.1, `PartTreeJpaQuery`), so write the
query by hand with `lower()`. `EXPLAIN` on the dev database: `lower(username) = ...` is an index scan on
`users_lower_username_key`, `upper(username) = ...` a sequential scan.

## Hashes: no salt column, no length limit

BCrypt embeds the salt inside the hash string, and Spring's `PasswordEncoder` has nowhere to hand you a separate
one. A `salt` column can only be filled by hand-rolled hashing.

Refresh-token hashes are unsalted SHA-256 on purpose: the token is random already, and the hash must come out the same
every time so the session can be looked up by it — see [auth](auth.md#refresh-tokens).

Don't constrain the length either. `BCryptPasswordEncoder` returns 60 characters, but the recommended
`createDelegatingPasswordEncoder()` prefixes `{bcrypt}` for 68, and Argon2 runs to about 97. That prefix records
which algorithm produced each hash, which is what lets old hashes keep verifying after a change.

## Repeat `id` / `created_at` / `updated_at` in every migration

Not `CREATE TABLE ... INHERITS` (constraints aren't inherited) and not `LIKE ... INCLUDING ALL` (a copy, not a
link). A migration is a historical record — it has to mean the same thing in three years on a fresh database, and
indirection breaks that.

## Once applied, a migration file is frozen

Flyway checksums the file as text, **comments included**, and refuses to start on a mismatch. Corrections are
always a new migration, never an edit to an applied one.

The one exception: **before the first production deploy**, migrations may be squashed, as the case-insensitive
username index was folded into `V2026.09.08_001`. Every schema that already ran them — local `public` and `test` —
must then be dropped, because Flyway also refuses an applied migration whose file no longer exists. Once a
production database exists, this door is closed.
