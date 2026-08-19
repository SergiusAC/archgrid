-- Which element is drawn over which, on this view.
--
-- Presentation, like the coordinates beside it: overlaps are unavoidable the moment a diagram has
-- containers and frames, and the order they resolve in was whatever order the elements happened to
-- reach the graph. Unversioned, last-write-wins, outside the commit log (ADR-0003).
alter table view_layout_node
    add column z integer;

comment on column view_layout_node.z is
    'Stacking order on this view. Presentation only (ADR-0003).';
