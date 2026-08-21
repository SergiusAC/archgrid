-- The same element, drawn more than once on one view.
--
-- Until now a box *was* an element: `view_layout_node` was keyed by (view, element), so an element
-- could appear on a diagram exactly once. The exchange format allows several nodes with the same
-- `elementRef` and so does every tool that reads it, which is the immediate reason (ADR-0012 §1
-- reversed by decision); the standing reason is that drawing a shared component twice is how an
-- architect keeps a diagram from turning into a bundle of crossing lines.
--
-- What changes is the layout and nothing above it. **Membership stays versioned and keyed by the
-- element**: `view_element` still answers "is this element on this view", which is the question
-- commits record and "where is this used" reads (ADR-0009). How many times it is *drawn* is
-- presentation, lives here, and is not a commit.
--
-- Two consequences follow, and both are in this file:
--
--   * nesting is between placements, not between elements — a copy may sit inside a container
--     while the original stands alone, which is the same reasoning as ADR-0011 one level down;
--   * a line has ends, and they are placements. Without that, a duplicate would be a box with no
--     lines, and drawing a second copy would be pointless.
alter table view_layout_node
    add column id uuid;

update view_layout_node
set id = gen_random_uuid();

alter table view_layout_node
    alter column id set not null;

-- Nesting, translated from element to placement while the old column still says which was which.
alter table view_layout_node
    add column parent_node_id uuid;

update view_layout_node child
set parent_node_id = parent.id
from view_layout_node parent
where parent.view_id = child.view_id
  and parent.element_id = child.parent_element_id;

alter table view_layout_node
    drop constraint view_layout_node_pkey;

alter table view_layout_node
    add constraint view_layout_node_pkey primary key (id);

alter table view_layout_node
    add constraint view_layout_node_parent_fkey foreign key (parent_node_id)
        references view_layout_node (id) on delete set null;

alter table view_layout_node
    drop column parent_element_id;

-- The old primary key was also the index for "everything on this view", which is the only way this
-- table is ever read.
create index view_layout_node_view_idx on view_layout_node (view_id);

-- Which placements a line runs between. Null means "wherever the element is drawn" — the state of
-- every line until somebody moves an end, and the answer for a view with no duplicates at all.
alter table view_layout_link
    add column source_node_id uuid references view_layout_node (id) on delete set null,
    add column target_node_id uuid references view_layout_node (id) on delete set null;

comment on column view_layout_node.id is
    'A placement: one drawing of an element on one view. An element may have several (ADR-0014).';
comment on column view_layout_link.source_node_id is
    'Which placement this end attaches to; null while the line follows the only one there is.';
