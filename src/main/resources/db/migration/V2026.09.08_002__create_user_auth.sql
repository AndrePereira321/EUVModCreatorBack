-- Password credentials, split 1:1 out of users so hashes never ride along in an ordinary user query.
-- No salt column: BCrypt embeds the salt inside the hash string it returns.
-- No length limit on password_hash: Spring's delegating encoder prefixes {bcrypt} (68 chars) and Argon2
-- is longer again, so any fixed width would force a migration on the next algorithm change.

create table user_auth
(
    id            uuid primary key     default gen_random_uuid(),
    user_id       uuid        not null unique references users (id) on delete cascade,
    password_hash text        not null,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);
