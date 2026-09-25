-- Core account record: one row per person who can sign in.
-- Deliberately holds no credentials — password hashes live in user_auth and sessions in user_sessions,
-- so this table stays safe to select freely and to expose through the API.

create table users
(
    id         uuid primary key     default gen_random_uuid(),
    username   text        not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- Usernames are unique regardless of case: "Andre" and "andre" are the same account, yet each user keeps the
-- casing they registered with. A unique constraint only takes plain columns, so the lower() needs an index.
-- Queries must filter on this exact expression, lower(username), or PostgreSQL can't use the index for the lookup.
create unique index users_lower_username_key on users (lower(username));
