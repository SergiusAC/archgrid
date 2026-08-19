-- How a line is routed on one view.
--
-- The bendpoints an architect drags into a connection so it goes round a box instead of through
-- it. Presentation, exactly like the coordinates it travels with: the relationship itself is the
-- same relationship whichever way its line is drawn, so this is unversioned, last-write-wins and
-- outside the commit log (ADR-0003).
--
-- Per view, because the same relationship is drawn on many diagrams and each of them has its own
-- boxes in the way.
--
-- jsonb rather than a row per point: the list is read and written whole, is never queried into,
-- and an ordered child table would need a sequence column for no gain.
create table view_layout_link (
    view_id         uuid        not null references view (id) on delete cascade,
    relationship_id uuid        not null references relationship (id) on delete cascade,
    vertices        jsonb       not null default '[]'::jsonb,
    updated_at      timestamptz not null default now(),

    primary key (view_id, relationship_id),
    constraint view_layout_link_vertices_is_array check (jsonb_typeof(vertices) = 'array')
);

comment on table view_layout_link is
    'Bendpoints of a connection on one view. Presentation only, never versioned (ADR-0003).';
