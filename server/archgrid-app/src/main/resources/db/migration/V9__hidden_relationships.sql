-- Which relationships this view does not draw.
--
-- The model keeps asserting them; one diagram simply declines to show them, the way nesting
-- already replaces a line with a box inside a box (ADR-0011). A view shows the relationships whose
-- endpoints are both on it, and without this there was no way to leave one out short of deleting
-- it from the model — a diagram-level wish answered with a repository-level change.
--
-- Presentation, so it sits with the coordinates: unversioned, last-write-wins, outside the commit
-- log (ADR-0003).
create table view_hidden_relationship (
    view_id         uuid        not null references view (id) on delete cascade,
    relationship_id uuid        not null references relationship (id) on delete cascade,
    updated_at      timestamptz not null default now(),

    primary key (view_id, relationship_id)
);

comment on table view_hidden_relationship is
    'Relationships this view does not draw. Presentation only; the model still asserts them (ADR-0003).';
