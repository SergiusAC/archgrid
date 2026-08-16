-- ArchGrid schema, migration 1: tenancy boundary and model registry.
--
-- Two conventions established here are load-bearing for everything that follows
-- (see docs/02-architecture.md §4 and ADR-0004):
--
--   1. Every table carries workspace_id. An on-premise install is usually single-tenant,
--      but workspace is the access-control boundary and the prod/sandbox separation, and
--      retrofitting it later would mean rewriting every query.
--
--   2. Every addressable object carries both a surrogate uuid and a slug ("key"). The uuid
--      is stable across renames and is what the system references; the slug is what humans,
--      URLs and the Architecture-as-Code format reference.

create table workspace (
    id          uuid        primary key,
    "key"       text        not null,
    name        text        not null,
    settings    jsonb       not null default '{}'::jsonb,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),

    constraint workspace_key_unique unique ("key"),
    -- Mirrors dev.archgrid.domain.shared.Slug. Duplicated deliberately: the database is
    -- the last line of defence against a bad import or a manual fix in production.
    constraint workspace_key_is_slug check ("key" ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length("key") <= 64),
    constraint workspace_name_not_blank check (length(btrim(name)) between 1 and 200)
);

comment on table workspace is
    'Isolation boundary for access control and for separating production, sandbox and archived landscapes.';

create table model (
    id             uuid        primary key,
    workspace_id   uuid        not null references workspace (id) on delete cascade,
    "key"          text        not null,
    name           text        not null,
    description    text,
    -- Populated from phase 2 onward, when the commit table exists. Left without a foreign
    -- key here rather than omitted, so that the column that makes the model versioned is
    -- present from the first migration.
    head_commit_id uuid,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),

    constraint model_key_unique_per_workspace unique (workspace_id, "key"),
    constraint model_key_is_slug check ("key" ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length("key") <= 64),
    constraint model_name_not_blank check (length(btrim(name)) between 1 and 200)
);

create index model_workspace_idx on model (workspace_id);

comment on table model is
    'An architecture model: the versioned graph of elements and relationships. head_commit_id gains its foreign key in phase 2.';
comment on column model.head_commit_id is
    'Current tip of the model history. Null until the first commit exists.';
