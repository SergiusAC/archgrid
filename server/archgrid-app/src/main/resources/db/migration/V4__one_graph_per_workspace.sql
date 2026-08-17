-- ArchGrid schema, migration 4: one connected graph per workspace.
--
-- Corrects a design error. The schema allowed several models inside one workspace, each with
-- its own elements, which meant "Kafka" in one model and "Kafka" in another were different
-- objects that could never be the same thing.
--
-- The damage was not the missing reuse; it was that "where is this used", impact analysis and
-- the phase 6 drift report would have answered from inside one model and been silently wrong
-- about the rest of the landscape. The product's entire proposition is that the graph is the
-- source of truth, and a source of truth cannot exist in three copies. See ADR-0008.
--
-- The workspace is therefore the repository. Its single model row is the graph. The many
-- named things an architect creates are views, which arrive in phase 4.

-- Every workspace gets a graph, so nothing has to check for its absence at read time.
insert into model (id, workspace_id, "key", name)
select gen_random_uuid(), w.id, 'graph', w.name
from workspace w
where not exists (select 1 from model m where m.workspace_id = w.id);

-- Refuse rather than guess. Merging two graphs means deciding what to do with colliding keys,
-- two commit histories and two sets of profiles; no migration should make those choices
-- silently. Pre-release there are no installations, so the practical answer is to drop the
-- development database.
do $$
declare
    offending int;
begin
    select count(*)
    into offending
    from (select workspace_id from model group by workspace_id having count(*) > 1) crowded;

    if offending > 0 then
        raise exception
            'Cannot enforce one graph per workspace: % workspace(s) hold more than one model. '
            'Move each extra model into its own workspace, or drop the database if this is a '
            'development instance.', offending;
    end if;
end $$;

alter table model
    add constraint model_one_per_workspace unique (workspace_id);

comment on table model is
    'The single architecture graph of a workspace. One row per workspace: the workspace is the repository (ADR-0008). Views over this graph arrive in phase 4.';
