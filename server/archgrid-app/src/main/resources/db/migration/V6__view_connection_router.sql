-- How a view routes its connections: presentation, so deliberately not versioned.
--
-- It lives on `view` beside `folder_id` rather than in `view_version` beside name and viewpoint,
-- because switching a diagram to orthogonal routing is not a documentation decision anybody needs
-- attributed — it is the same kind of thing as where the boxes sit (ADR-0003).
--
-- Archi makes the same split and the same choice of default: connections are straight lines with
-- bend points, and orthogonal routing is a per-view option that forbids bend points.
alter table view
    add column router text not null default 'straight'
        constraint view_router_known check (router in ('straight', 'orthogonal'));

comment on column view.router is
    'Presentation only, last-write-wins, outside the commit log (ADR-0003).';
