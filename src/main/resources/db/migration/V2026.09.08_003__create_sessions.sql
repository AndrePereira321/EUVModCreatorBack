-- One row per sign-in. Access tokens are short-lived JWTs and carry no server state, so this table is
-- what makes revocation possible: deleting or revoking the row ends the session.
-- Only the hash of the refresh token is stored — the raw token exists solely in the client's cookie.
-- revoked_at null means the session is still live.

create table user_sessions
(
    id                 uuid primary key     default gen_random_uuid(),
    user_id            uuid        not null references users (id) on delete cascade,
    refresh_token_hash text        not null unique,
    expires_at         timestamptz not null,
    revoked_at         timestamptz,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),

    constraint user_sessions_expires_after_creation check (expires_at > created_at)
);

create index user_sessions_user_id_idx on user_sessions (user_id);
