-- One identity per object, step three of three: the rules become indexes, and the slug goes.
--
-- V16 added the columns, V17 made the data obey; this file is the point of no return. After it, an
-- element's name is held by exactly one current element per model, a view's name likewise, and a
-- relationship's (type, source, target) is stated exactly once. The application checks all three
-- in memory and explains a refusal in words (ModelChange); these indexes are the last line of
-- defence behind it — two commits racing, a hand-made fix in production — and the application
-- turns their refusal into a 409 rather than a 500.
--
-- The comparison is the one the domain uses: lower(btrim(name)). Deleted objects have a null name
-- or a null triple and are outside the index, which is what frees a name for reuse.
create unique index element_name_unique_per_model
    on element (model_id, lower(btrim(name)))
    where name is not null;

create unique index view_name_unique_per_model
    on view (model_id, lower(btrim(name)))
    where name is not null;

create unique index relationship_triple_unique_per_model
    on relationship (model_id, concept_type, source_id, target_id)
    where source_id is not null;

-- The slug columns. Dropping a column drops the constraints that rested on it
-- (*_key_unique_per_model, *_key_is_slug). Nothing reads them since V16's code; nothing wrote them
-- either, which is why this had to wait for the code and not merely for V17.
--
-- Rollback from here is a backup: the slugs are not kept anywhere else. They were never referenced
-- by anything but URLs, and a URL that names a slug now answers 400 with the shape it expects.
alter table element
    drop column "key";

alter table relationship
    drop column "key";

alter table view
    drop column "key";

comment on table element is
    'Identity of an element: one UUID, never reused, never deleted. Its name is unique per model while it lives (docs/09).';
comment on table relationship is
    'Identity of a relationship, containment included (ADR-0011). Its type and ends are unique per model while it lives (docs/09).';
comment on table view is
    'Identity of a view. Its name is unique per model while it lives (docs/09).';
