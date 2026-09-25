-- Usernames are unique regardless of case: "Andre" and "andre" are the same account, yet each user keeps the
-- casing they registered with. A unique index on lower(username) enforces that, and replaces the plain unique
-- constraint, which it makes redundant.
-- lower() must match the expression UserRepository queries with, or PostgreSQL can't use this index for the lookup.

alter table users drop constraint users_username_key;

create unique index users_lower_username_key on users (lower(username));
