-- One identity per object, step one of three: the columns the new rules rest on (docs/09).
--
-- Every element, relationship and view carried two identities: a UUID the system referenced and a
-- slug people were meant to. Nobody read the slug, the editor synthesised it from the name and
-- truncated it, the importer transliterated it out of whatever language the file was in, and both
-- had edge cases the check constraint refused. Archi has one opaque identifier per object; so do we.
--
-- What replaces the slug as the human-facing handle is the name, with a rule behind it: element
-- names are unique in a repository, view names too, and a relationship is unique by its type and
-- its two ends. Those rules need columns on the identity rows — the versioned tables cannot carry
-- a uniqueness index over "current rows of one model" without denormalising model_id into them,
-- and the identity rows already have it.
--
-- This file adds and fills the columns. V17 (Java) resolves the duplicates real data may hold, as
-- a commit, and V18 adds the indexes and drops the slug columns. Three files so that the drop is
-- a separable step.

-- ---------------------------------------------------------------------------- element.name
-- The current name, mirrored from the open version row. Null once the element is deleted: the
-- identity row outlives the element for the history's sake, and a name still on it would block
-- the next element to take that name.
alter table element
    add column name text;

update element e
set name = v.name
from element_version v
where v.element_id = e.id and v.to_seq is null;

alter table element
    add constraint element_name_shape check (name is null or length(btrim(name)) between 1 and 200);

-- ---------------------------------------------------------------------------- view.name
alter table view
    add column name text;

update view v
set name = vv.name
from view_version vv
where vv.view_id = v.id and vv.to_seq is null;

alter table view
    add constraint view_name_shape check (name is null or length(btrim(name)) between 1 and 200);

-- ---------------------------------------------------------------------------- relationship triple
-- Type and ends never change while a relationship lives — there is no command to retype or
-- retarget one — so mirroring them here is not a second source of truth, merely the current one
-- kept where an index can see it. All three null together, once the relationship is deleted.
alter table relationship
    add column concept_type text,
    add column source_id uuid references element (id) on delete cascade,
    add column target_id uuid references element (id) on delete cascade;

update relationship r
set concept_type = v.concept_type,
    source_id = v.source_id,
    target_id = v.target_id
from relationship_version v
where v.relationship_id = r.id and v.to_seq is null;

alter table relationship
    add constraint relationship_triple_all_or_nothing
        check ((concept_type is null) = (source_id is null) and (source_id is null) = (target_id is null));

create index relationship_source_idx on relationship (source_id) where source_id is not null;
create index relationship_target_idx on relationship (target_id) where target_id is not null;

-- ---------------------------------------------------------------------------- line keys
-- `view_layout_link.link_key` named the line by the relationship's slug, or by a derived key
-- assembled from two element slugs. Rewritten to ids while the slug columns are still here to
-- join on. A derived key whose elements no longer exist has no id to rewrite to and is dropped:
-- nothing would ever draw that line again.
update view_layout_link l
set link_key = r.id::text
from relationship r
join view v on v.model_id = r.model_id
where v.id = l.view_id and r."key" = l.link_key;

update view_layout_link l
set link_key = 'derived:' || s.id || ':' || split_part(l.link_key, ':', 3) || ':' || t.id
from view v, element s, element t
where v.id = l.view_id
  and l.link_key like 'derived:%'
  and s.model_id = v.model_id and s."key" = split_part(l.link_key, ':', 2)
  and t.model_id = v.model_id and t."key" = split_part(l.link_key, ':', 4);

delete from view_layout_link
where link_key like 'derived:%'
  and link_key !~ '^derived:[0-9a-f-]{36}:[a-z]+:[0-9a-f-]{36}$';

comment on column element.name is
    'The current name, mirrored from the open version. Unique per model from V18; null once deleted.';
comment on column view.name is
    'The current name, mirrored from the open version. Unique per model from V18; null once deleted.';
comment on column relationship.concept_type is
    'Mirrors the open version with source_id and target_id; the three are unique per model from V18 and null once deleted.';
comment on column view_layout_link.link_key is
    'What the line stands for: a relationship id, or derived:<source id>:<type>:<target id> for a derived line. Not unique since V15.';
