-- The same relationship, drawn more than once on one view — and the integrity the layout
-- references owed since V14.
--
-- V14 gave a box its own identity; a line still had none: `view_layout_link` was keyed by
-- (view_id, link_key), so a relationship got exactly one line per view. With duplicates of an
-- element on the diagram that is not enough — the exchange format allows several `connection`
-- elements for one `relationshipRef`, Archi draws them, and an import would have collapsed them
-- silently (ADR-0014, D-002). So the line becomes what the box became: a placement, identified by
-- itself, saying which relationship it stands for.
alter table view_layout_link
    add column id uuid;

update view_layout_link
set id = gen_random_uuid();

alter table view_layout_link
    alter column id set not null;

alter table view_layout_link
    drop constraint view_layout_link_pkey;

alter table view_layout_link
    add constraint view_layout_link_pkey primary key (id);

-- The old key was also the read path: everything on this view, the only way the table is read.
create index view_layout_link_view_idx on view_layout_link (view_id);

-- The audit's second finding (ADR-0014 A2): nothing tied a layout reference to its own view — a
-- parent or a line's end could name a box on a different diagram, and only the client stood in
-- the way. Composite keys close that: the referenced placement must carry the same view_id.
--
-- `on delete set null (source_node_id)` nulls only the named column — nulling the whole key would
-- try to null view_id, which is not nullable and not what losing a box means.
alter table view_layout_node
    add constraint view_layout_node_view_id_unique unique (view_id, id);

alter table view_layout_node
    drop constraint view_layout_node_parent_fkey;

alter table view_layout_node
    add constraint view_layout_node_parent_fkey
        foreign key (view_id, parent_node_id) references view_layout_node (view_id, id)
        on delete set null (parent_node_id);

alter table view_layout_link
    drop constraint view_layout_link_source_node_id_fkey,
    drop constraint view_layout_link_target_node_id_fkey;

alter table view_layout_link
    add constraint view_layout_link_source_node_fkey
        foreign key (view_id, source_node_id) references view_layout_node (view_id, id)
        on delete set null (source_node_id),
    add constraint view_layout_link_target_node_fkey
        foreign key (view_id, target_node_id) references view_layout_node (view_id, id)
        on delete set null (target_node_id);

comment on column view_layout_link.id is
    'One line on one view. A relationship may be drawn by several (ADR-0014, V15).';
comment on column view_layout_link.link_key is
    'What the line stands for: a relationship key, or a computed key for a derived line. Not unique since V15.';
