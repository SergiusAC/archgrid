-- Nesting is a property of one view, not of the element (ADR-0011).
--
-- Archi treats it this way and the reason is decisive: an element appears on any number of views,
-- and each is entitled to show it differently. With containment on the element, nesting Payment
-- API inside Payment System on the context diagram nested it on every diagram — which contradicts
-- the reason views exist at all.
--
-- It sits with the coordinates, not with the membership: which box is drawn inside which is
-- presentation, last-write-wins, outside the commit log (ADR-0003).
alter table view_layout_node
    add column parent_element_id uuid references element (id) on delete set null;

comment on column view_layout_node.parent_element_id is
    'Drawn inside this element on this view. Presentation only (ADR-0011).';
