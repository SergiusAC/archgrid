-- ArchGrid schema, migration 3: the versioned semantic model.
--
-- This is the layer ADR-0003 calls the semantic model: elements, relationships and their
-- types. View definitions and layout arrive in phase 4 and are versioned differently or not
-- at all -- dragging a box must never produce a commit.
--
-- VERSIONING STRATEGY. docs/02-architecture.md sketched *_version tables plus materialised
-- *_current tables. This uses validity ranges over a per-model commit sequence instead:
-- every version row carries [from_seq, to_seq), with to_seq null meaning "still current".
--
--   state at HEAD      -> where to_seq is null
--   state at commit N  -> where from_seq <= N and (to_seq is null or to_seq > N)
--
-- Both are single indexed scans with no recursion and no second table to keep consistent in
-- the same transaction. The materialised variant buys nothing here and costs a whole class of
-- "current disagrees with history" defects. This works because history is linear in v1;
-- branching (deferred past phase 9) would need the ancestry walk the original sketch implied.

create table model_commit (
    id         uuid        primary key,
    model_id   uuid        not null references model (id) on delete cascade,
    -- Monotonic per model. Assigned under a row lock on model, which also serialises
    -- concurrent commits -- exactly the behaviour the optimistic check on baseCommitId wants.
    seq        bigint      not null,
    parent_id  uuid        references model_commit (id),
    author     text        not null,
    message    text,
    created_at timestamptz not null default now(),

    constraint model_commit_seq_unique unique (model_id, seq),
    constraint model_commit_seq_positive check (seq > 0)
);

create index model_commit_model_seq_idx on model_commit (model_id, seq desc);

alter table model
    add constraint model_head_commit_fk foreign key (head_commit_id) references model_commit (id);

-- Identity tables. A UUID that never changes and a slug that humans, URLs and the future
-- Architecture-as-Code format use. Renaming an element changes neither.
create table element (
    id       uuid primary key,
    model_id uuid not null references model (id) on delete cascade,
    "key"    text not null,

    constraint element_key_unique_per_model unique (model_id, "key"),
    constraint element_key_is_slug check ("key" ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length("key") <= 64)
);

create table relationship (
    id       uuid primary key,
    model_id uuid not null references model (id) on delete cascade,
    "key"    text not null,

    constraint relationship_key_unique_per_model unique (model_id, "key"),
    constraint relationship_key_is_slug check ("key" ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length("key") <= 64)
);

create table element_version (
    element_id        uuid   not null references element (id) on delete cascade,
    from_seq          bigint not null,
    to_seq            bigint,
    -- An ArchiMate concept name, e.g. 'ApplicationComponent'. Always the *base* concept:
    -- a profiled element stores its base here and the profile alongside, because everything
    -- downstream validates against the base type (ADR-0002).
    concept_type      text   not null,
    profile_id        uuid   references profile (id) on delete restrict,
    name              text   not null,
    documentation     text,
    properties        jsonb  not null default '{}'::jsonb,
    parent_element_id uuid   references element (id) on delete cascade,

    primary key (element_id, from_seq),
    constraint element_version_range check (to_seq is null or to_seq > from_seq),
    constraint element_version_name_not_blank check (length(btrim(name)) between 1 and 200),
    constraint element_version_properties_is_object check (jsonb_typeof(properties) = 'object'),
    constraint element_version_not_own_parent check (parent_element_id is null or parent_element_id <> element_id)
);

-- Partial index: the overwhelmingly common query is "the model as it is now".
create unique index element_version_current_idx on element_version (element_id) where to_seq is null;
create index element_version_parent_idx on element_version (parent_element_id) where to_seq is null;

create table relationship_version (
    relationship_id uuid   not null references relationship (id) on delete cascade,
    from_seq        bigint not null,
    to_seq          bigint,
    concept_type    text   not null,
    -- Endpoints are elements. ArchiMate also permits a relationship to target another
    -- relationship; that is deliberately out of scope for phase 2 and would be a nullable
    -- second pair of endpoint columns rather than a change to anything here.
    source_id       uuid   not null references element (id) on delete cascade,
    target_id       uuid   not null references element (id) on delete cascade,
    name            text,
    properties      jsonb  not null default '{}'::jsonb,

    primary key (relationship_id, from_seq),
    constraint relationship_version_range check (to_seq is null or to_seq > from_seq),
    constraint relationship_version_properties_is_object check (jsonb_typeof(properties) = 'object')
);

create unique index relationship_version_current_idx on relationship_version (relationship_id) where to_seq is null;
create index relationship_version_source_idx on relationship_version (source_id) where to_seq is null;
create index relationship_version_target_idx on relationship_version (target_id) where to_seq is null;

comment on table model_commit is
    'One atomic, attributed change to the semantic model. Layout changes never appear here.';
comment on column element_version.to_seq is
    'Null while this version is current. Deleting an element closes its version rather than removing the row.';
