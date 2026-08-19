-- A derived line can be routed by hand too, and its route has to survive.
--
-- `view_layout_link` keyed its rows by a foreign key into `relationship`, which quietly excluded
-- every line the model implies rather than states: a derived relationship has no row there, its key
-- is computed (`derived:source:type:target`), and the insert simply matched nothing. The bendpoints
-- were accepted by the editor, sent to the server and dropped without a word.
--
-- So the identity becomes the key of the line as the diagram knows it, and the foreign key becomes
-- an optional convenience: where the line stands for a real relationship, the reference keeps the
-- row cleaned up when that relationship is deleted. A derived line has nothing to point at, and
-- its row simply outlives a derivation that stops holding — harmless, since it is keyed by exactly
-- the source, type and target the derivation would produce again.
alter table view_layout_link
    add column link_key text;

update view_layout_link l
set link_key = r."key"
from relationship r
where r.id = l.relationship_id;

-- The old key goes first: PostgreSQL refuses to drop NOT NULL from a column while a primary key
-- still rests on it.
alter table view_layout_link
    drop constraint view_layout_link_pkey;

alter table view_layout_link
    alter column link_key set not null,
    alter column relationship_id drop not null;

alter table view_layout_link
    add constraint view_layout_link_pkey primary key (view_id, link_key);

comment on column view_layout_link.link_key is
    'The line as the diagram knows it: a relationship key, or a computed key for a derived line.';
comment on column view_layout_link.relationship_id is
    'Set when the line stands for a stated relationship; null for a derived one.';
