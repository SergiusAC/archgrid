package dev.archgrid.metamodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.profile.AttributeDefinition;
import dev.archgrid.domain.profile.AttributeType;
import dev.archgrid.domain.profile.Profile;
import dev.archgrid.domain.profile.ProfileCatalog;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Profiles exercised against the real 62-concept metamodel rather than a miniature. */
class ProfilesAgainstRealMetamodelTest {

    private static Metamodel metamodel;

    @BeforeAll
    static void loadFromClasspath() throws IOException {
        try (InputStream source =
                ProfilesAgainstRealMetamodelTest.class.getResourceAsStream("/metamodel/archimate-3.2.yaml")) {
            metamodel = MetamodelLoader.load(source);
        }
    }

    @Test
    @DisplayName("every concept but one can be specialised, and 'Relationship' is that one")
    void specialisability_matches_the_matrix() {
        var notSpecialisable = metamodel.elementTypes().stream()
                .map(ElementType::concept)
                .filter(concept -> !metamodel.check(concept, concept, "Specialization").isPermitted())
                .toList();

        // Pinned rather than merely observed: this is the rule ProfileCatalog derives from the
        // matrix instead of hard-coding, so a change upstream should surface here.
        assertThat(notSpecialisable).containsExactly("Relationship");
    }

    @Test
    @DisplayName("a realistic profile set loads and resolves against the real language")
    void a_realistic_catalogue_resolves() {
        ProfileCatalog catalog = ProfileCatalog.builder()
                .profile(Profile.of(
                        "Microservice",
                        "ApplicationComponent",
                        AttributeDefinition.of("owner-team", "Owning team", AttributeType.STRING),
                        AttributeDefinition.of("repository-url", "Repository", AttributeType.STRING)))
                .profile(Profile.of("Kafka Topic", "SystemSoftware"))
                .profile(Profile.of(
                        "Managed Database",
                        "SystemSoftware",
                        AttributeDefinition.of("provider", "Cloud provider", AttributeType.STRING)))
                .profile(Profile.of("Public API", "ApplicationService"))
                .build(metamodel);

        assertThat(catalog.all()).hasSize(4);
        assertThat(catalog.profilesFor("SystemSoftware"))
                .extracting(Profile::name)
                .containsExactly("Kafka Topic", "Managed Database");

        var microservice = catalog.resolve("Microservice").orElseThrow();
        assertThat(microservice.base().concept()).isEqualTo("ApplicationComponent");
        assertThat(microservice.displayType()).isEqualTo("Microservice");
    }

    @Test
    @DisplayName("a profiled element is validated exactly as its base concept")
    void profiles_do_not_change_what_is_permitted() {
        ProfileCatalog catalog = ProfileCatalog.builder()
                .profile(Profile.of("Microservice", "ApplicationComponent"))
                .profile(Profile.of("Kafka Topic", "SystemSoftware"))
                .build(metamodel);

        String microservice = catalog.resolve("Microservice").orElseThrow().base().concept();
        String topic = catalog.resolve("Kafka Topic").orElseThrow().base().concept();

        // The idea from the product brief: Payment Service -> Kafka -> Notification Service,
        // written with specialisations rather than bare ArchiMate types.
        assertThat(metamodel.check(microservice, topic, "Flow").isPermitted()).isTrue();
        assertThat(metamodel.check(topic, microservice, "Serving").isPermitted()).isTrue();

        assertThat(metamodel.permittedBetween(microservice, topic))
                .isEqualTo(metamodel.permittedBetween("ApplicationComponent", "SystemSoftware"));
    }

    @Test
    @DisplayName("a profile named after a real element type is refused against the real language")
    void rejects_collisions_with_the_real_language() {
        assertThatThrownBy(() -> ProfileCatalog.builder()
                        .profile(Profile.of("System Software", "ApplicationComponent"))
                        .build(metamodel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collides with the display name");
    }
}
