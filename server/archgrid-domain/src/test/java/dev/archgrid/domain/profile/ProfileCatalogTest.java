package dev.archgrid.domain.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.archgrid.domain.metamodel.Aspect;
import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Layer;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.RelationshipCategory;
import dev.archgrid.domain.metamodel.RelationshipType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProfileCatalogTest {

    private static final RelationshipType SPECIALIZATION =
            new RelationshipType("Specialization", "Specialization", 's', RelationshipCategory.OTHER, 0);
    private static final RelationshipType SERVING =
            new RelationshipType("Serving", "Serving", 'v', RelationshipCategory.DEPENDENCY, 0);

    private static final ElementType COMPONENT =
            new ElementType("ApplicationComponent", "Application Component", Layer.APPLICATION, Aspect.ACTIVE_STRUCTURE);
    private static final ElementType SERVICE =
            new ElementType("ApplicationService", "Application Service", Layer.APPLICATION, Aspect.BEHAVIOR);
    /** Stands in for the one concept the real matrix refuses to let specialise itself. */
    private static final ElementType RELATIONSHIP =
            new ElementType("Relationship", "Relationship", Layer.OTHER, Aspect.NONE);

    private static final Metamodel METAMODEL = Metamodel.builder()
            .version("3.2")
            .relationshipType(SPECIALIZATION)
            .relationshipType(SERVING)
            .elementType(COMPONENT)
            .elementType(SERVICE)
            .elementType(RELATIONSHIP)
            .permit("ApplicationComponent", "ApplicationComponent", List.of(SPECIALIZATION))
            .permit("ApplicationService", "ApplicationService", List.of(SPECIALIZATION))
            .permit("ApplicationComponent", "ApplicationService", List.of(SERVING))
            .permit("Relationship", "Relationship", List.of())
            .build();

    private static final Profile MICROSERVICE = Profile.of(
            "Microservice",
            "ApplicationComponent",
            AttributeDefinition.of("owner-team", "Owning team", AttributeType.STRING),
            AttributeDefinition.of("slo-availability", "Availability SLO", AttributeType.NUMBER));

    @Test
    @DisplayName("a profile resolves to its base type, which is what keeps validation honest")
    void resolves_a_profile_to_its_base_type() {
        ProfileCatalog catalog =
                ProfileCatalog.builder().profile(MICROSERVICE).build(METAMODEL);

        ProfileCatalog.ResolvedType resolved = catalog.resolve("Microservice").orElseThrow();

        assertThat(resolved.base()).isEqualTo(COMPONENT);
        assertThat(resolved.isSpecialized()).isTrue();
        assertThat(resolved.profile()).contains(MICROSERVICE);
        assertThat(resolved.displayType()).isEqualTo("Microservice");
    }

    @Test
    void resolves_a_plain_concept_without_a_profile() {
        ProfileCatalog catalog = ProfileCatalog.empty(METAMODEL);

        ProfileCatalog.ResolvedType resolved =
                catalog.resolve("ApplicationComponent").orElseThrow();

        assertThat(resolved.base()).isEqualTo(COMPONENT);
        assertThat(resolved.isSpecialized()).isFalse();
        assertThat(resolved.displayType()).isEqualTo("Application Component");
    }

    @Test
    @DisplayName("a profiled element obeys exactly the relationship rules of its base type")
    void specialization_never_widens_or_narrows_what_is_permitted() {
        ProfileCatalog catalog =
                ProfileCatalog.builder().profile(MICROSERVICE).build(METAMODEL);

        String base = catalog.resolve("Microservice").orElseThrow().base().concept();

        assertThat(METAMODEL.check(base, "ApplicationService", "Serving").isPermitted())
                .isTrue();
        assertThat(METAMODEL.check(base, "ApplicationService", "Specialization").isPermitted())
                .isFalse();
        assertThat(METAMODEL.permittedBetween(base, "ApplicationService"))
                .isEqualTo(METAMODEL.permittedBetween("ApplicationComponent", "ApplicationService"));
    }

    @Test
    void unknown_type_references_resolve_to_nothing() {
        ProfileCatalog catalog = ProfileCatalog.empty(METAMODEL);

        assertThat(catalog.resolve("Microservice")).isEmpty();
    }

    @Test
    void groups_profiles_by_the_concept_they_specialise() {
        ProfileCatalog catalog = ProfileCatalog.builder()
                .profile(MICROSERVICE)
                .profile(Profile.of("Legacy Monolith", "ApplicationComponent"))
                .profile(Profile.of("Public API", "ApplicationService"))
                .build(METAMODEL);

        assertThat(catalog.profilesFor("ApplicationComponent"))
                .extracting(Profile::name)
                .containsExactly("Microservice", "Legacy Monolith");
        assertThat(catalog.profilesFor("ApplicationService")).extracting(Profile::name).containsExactly("Public API");
        assertThat(catalog.profilesFor("Relationship")).isEmpty();
    }

    @Test
    void rejects_a_profile_of_a_type_the_metamodel_does_not_declare() {
        assertThatThrownBy(() -> ProfileCatalog.builder()
                        .profile(Profile.of("Microservice", "Microservice"))
                        .build(METAMODEL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not declare");
    }

    @Test
    @DisplayName("specialisability is read from the matrix, not hard-coded")
    void rejects_a_profile_of_a_concept_that_cannot_specialise_itself() {
        assertThatThrownBy(() -> ProfileCatalog.builder()
                        .profile(Profile.of("Odd", "Relationship"))
                        .build(METAMODEL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be specialised");
    }

    @Test
    @DisplayName("a profile may not shadow a language type, by concept or by display name")
    void rejects_names_that_collide_with_the_language() {
        assertThatThrownBy(() -> ProfileCatalog.builder()
                        .profile(Profile.of("ApplicationComponent", "ApplicationComponent"))
                        .build(METAMODEL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collides with an ArchiMate element type");

        assertThatThrownBy(() -> ProfileCatalog.builder()
                        .profile(Profile.of("application component", "ApplicationComponent"))
                        .build(METAMODEL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collides with the display name");
    }

    @Test
    @DisplayName("names are unique within a model, which is stricter than Archi on purpose")
    void rejects_duplicate_profile_names() {
        assertThatThrownBy(() -> ProfileCatalog.builder()
                        .profile(MICROSERVICE)
                        .profile(Profile.of("Microservice", "ApplicationService")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be unique within a model");
    }

    @Test
    void rejects_duplicate_attribute_keys() {
        assertThatThrownBy(() -> Profile.of(
                        "Microservice",
                        "ApplicationComponent",
                        AttributeDefinition.of("owner-team", "Owning team", AttributeType.STRING),
                        AttributeDefinition.of("owner-team", "Owner", AttributeType.STRING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
    }

    @Test
    @DisplayName("attribute types are exactly the exchange format's six, so export loses nothing")
    void attribute_types_match_the_exchange_format() {
        assertThat(AttributeType.values())
                .extracting(AttributeType::code)
                .containsExactly("string", "boolean", "currency", "date", "time", "number");
        assertThat(AttributeType.fromCode("currency")).isEqualTo(AttributeType.CURRENCY);
        assertThatThrownBy(() -> AttributeType.fromCode("uuid")).isInstanceOf(IllegalArgumentException.class);
    }
}
