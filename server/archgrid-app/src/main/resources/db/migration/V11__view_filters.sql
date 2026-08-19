-- What a view draws, on top of what belongs to it.
--
-- Versioned with the rest of the view definition (ADR-0003): "this diagram is about the technology
-- layer" is a documentation decision worth attributing, exactly like its name or its viewpoint.
-- Membership is untouched by a filter, which is what makes removing one bring the elements back
-- where they were — their coordinates never went anywhere.
--
-- jsonb rather than a table: a filter set is small, always read whole, never joined against, and
-- lives inside a versioned row whose validity ranges a child table would have to mirror exactly.
alter table view_version
    add column filters jsonb not null default '[]'::jsonb;

alter table view_version
    add constraint view_version_filters_is_array check (jsonb_typeof(filters) = 'array');

comment on column view_version.filters is
    'Conditions on what this view draws. Never on what belongs to it (ADR-0003).';
