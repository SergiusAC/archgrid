-- ArchGrid schema, migration 5: views and folders.
--
-- A view is the thing an architect creates and names: a context diagram of one system, the
-- modules of another, the course of a process. It is a viewpoint plus a selection of elements
-- from the repository, and the same element appears in as many views as it belongs to (ADR-0009).
--
-- Two different notions of "inside" live here and must not be confused:
--
--   folder        organisation only. Means nothing to the metamodel: no effect on validation,
--                 on relationship derivation or on C4 level. Not versioned.
--   parent        ArchiMate composition. Semantic, versioned, and what C4 depth is computed
--                 from. Already on element_version.
--
-- Confusing them would produce a product where moving an element to a different folder changes
-- what a context diagram shows.

-- ---------------------------------------------------------------------------- folders

create table folder (
    id         uuid        primary key,
    model_id   uuid        not null references model (id) on delete cascade,
    parent_id  uuid        references folder (id) on delete cascade,
    -- Two trees, not one: an inventory and a set of diagrams do not belong in the same list.
    kind       text        not null check (kind in ('elements', 'views')),
    name       text        not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint folder_name_not_blank check (length(btrim(name)) between 1 and 120),
    constraint folder_not_own_parent check (parent_id is null or parent_id <> id)
);

-- Sibling names are unique. Two indexes because PostgreSQL treats NULLs as distinct, so a
-- single constraint would not catch duplicates at the root of a tree.
create unique index folder_unique_child on folder (model_id, parent_id, kind, lower(name))
    where parent_id is not null;
create unique index folder_unique_root on folder (model_id, kind, lower(name))
    where parent_id is null;
create index folder_parent_idx on folder (parent_id);

comment on table folder is
    'Organisation only. A folder has no meaning in the metamodel and is deliberately not versioned (ADR-0009).';

-- Mutable, like layout: reorganising is tidying, not an architecture change, and putting it in
-- the commit log would bury "added a relationship" under "moved to a folder".
alter table element
    add column folder_id uuid references folder (id) on delete set null;
create index element_folder_idx on element (folder_id);

-- ---------------------------------------------------------------------------- views

create table view (
    id        uuid primary key,
    model_id  uuid not null references model (id) on delete cascade,
    "key"     text not null,
    folder_id uuid references folder (id) on delete set null,

    constraint view_key_unique_per_model unique (model_id, "key"),
    constraint view_key_is_slug check ("key" ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length("key") <= 64)
);

create index view_folder_idx on view (folder_id);

-- Name and viewpoint are versioned: "this diagram is now a Layered view" is a documentation
-- decision worth attributing, exactly like adding an element to it.
create table view_version (
    view_id   uuid   not null references view (id) on delete cascade,
    from_seq  bigint not null,
    to_seq    bigint,
    name      text   not null,
    -- A viewpoint code from the metamodel file. Validated by the application, deliberately not
    -- a foreign key: the metamodel is a versioned file, not a table.
    viewpoint text   not null,

    primary key (view_id, from_seq),
    constraint view_version_range check (to_seq is null or to_seq > from_seq),
    constraint view_version_name_not_blank check (length(btrim(name)) between 1 and 200)
);

create unique index view_version_current_idx on view_version (view_id) where to_seq is null;

-- Which elements are on the view. Versioned membership, validity ranges as everywhere else.
--
-- Relationships are NOT listed. A view shows every relationship whose two endpoints are both on
-- it, which is what an architect expects after dropping two elements next to each other, and it
-- removes a whole table plus the question of what happens when an endpoint leaves.
create table view_element (
    view_id    uuid   not null references view (id) on delete cascade,
    element_id uuid   not null references element (id) on delete cascade,
    from_seq   bigint not null,
    to_seq     bigint,

    primary key (view_id, element_id, from_seq),
    constraint view_element_range check (to_seq is null or to_seq > from_seq)
);

create unique index view_element_current_idx on view_element (view_id, element_id) where to_seq is null;
create index view_element_by_element_idx on view_element (element_id) where to_seq is null;

-- Coordinates. Never versioned (ADR-0003): dragging a box must not produce a commit.
create table view_layout_node (
    view_id    uuid        not null references view (id) on delete cascade,
    element_id uuid        not null references element (id) on delete cascade,
    x          double precision not null,
    y          double precision not null,
    width      double precision,
    height     double precision,
    updated_at timestamptz not null default now(),

    primary key (view_id, element_id)
);

comment on table view_layout_node is
    'Mutable coordinates, last-write-wins. Deliberately outside the commit log (ADR-0003).';
comment on table view_element is
    'Versioned membership. Relationships are not listed: a view shows those whose endpoints are both present.';
