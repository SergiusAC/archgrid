-- ArchGrid schema, migration 2: profiles (ArchiMate specialization of concepts).
--
-- A profile specialises an element type within one model: "Microservice" as a kind of
-- Application Component, "Kafka Topic" as a kind of System Software. This is ArchiMate's own
-- extension mechanism -- the specification calls it Specialization of Concepts, Archi calls it
-- a Profile, the exchange format calls it a Stereotype -- and it is how a customer extends the
-- language without the product giving up conformance. See ADR-0002.
--
-- The invariant it preserves: a profiled element remains an element of its base type and
-- obeys exactly the relationship rules of that type. Nothing here may weaken that, which is
-- why concept_type is a plain concept name validated against the loaded metamodel rather than
-- anything the database could be tempted to reinterpret.
--
-- element_version gains a nullable profile_id in phase 2, when that table is created. The
-- profile table arrives first for the same reason commit_id exists before the history UI:
-- adding a dimension to element storage after the fact means revisiting every element query.

create table profile (
    id           uuid        primary key,
    model_id     uuid        not null references model (id) on delete cascade,
    name         text        not null,
    -- An ArchiMate element type concept, e.g. 'ApplicationComponent'. Intentionally not a
    -- foreign key: the metamodel is a versioned file loaded at startup, not a table, and
    -- projecting it into the database would create two sources of truth for the language.
    -- The application validates this against the loaded metamodel and refuses unknown values.
    concept_type text        not null,
    -- Attribute definitions: [{"key": "owner-team", "name": "Owning team", "type": "string"}]
    -- The six permitted types are those of The Open Group exchange format, so a model stays
    -- exportable without loss.
    attributes   jsonb       not null default '[]'::jsonb,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),

    -- Unique per model, not per concept type. A palette entry and a DSL identifier have to
    -- mean exactly one thing; this is stricter than Archi, and an import carrying a collision
    -- must report it rather than silently keeping one of the two.
    constraint profile_name_unique_per_model unique (model_id, name),
    constraint profile_name_not_blank check (length(btrim(name)) between 1 and 100),
    constraint profile_concept_type_not_blank check (length(btrim(concept_type)) between 1 and 100),
    constraint profile_attributes_is_array check (jsonb_typeof(attributes) = 'array')
);

create index profile_model_idx on profile (model_id);
create index profile_concept_type_idx on profile (model_id, concept_type);

comment on table profile is
    'A specialization of an ArchiMate element type within one model. Adds vocabulary and attributes; never changes which relationships are permitted.';
comment on column profile.concept_type is
    'ArchiMate element type concept being specialised. Validated against the metamodel file at application level, deliberately not a foreign key.';
