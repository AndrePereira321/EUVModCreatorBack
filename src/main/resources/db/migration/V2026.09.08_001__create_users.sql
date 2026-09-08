-- Core account record: one row per person who can sign in.
-- Deliberately holds no credentials — password hashes live in user_auth and sessions in user_sessions,
-- so this table stays safe to select freely and to expose through the API.

create table users
(
    id         uuid primary key     default gen_random_uuid(),
    username   text        not null unique,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
