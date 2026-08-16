package dev.archgrid.metamodel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.archgrid.domain.metamodel.Aspect;
import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Layer;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.RelationshipCategory;
import dev.archgrid.domain.metamodel.RelationshipType;
import dev.archgrid.domain.metamodel.RelationshipVerdict;
import dev.archgrid.domain.metamodel.Viewpoint;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Loads the real metamodel from the classpath and checks what it actually contains. */
class MetamodelLoaderTest {

    private static Metamodel metamodel;

    @BeforeAll
    static void loadFromClasspath() throws IOException {
        try (InputStream source =
                MetamodelLoaderTest.class.getResourceAsStream("/metamodel/archimate-3.2.yaml")) {
            assertThat(source)
                    .describedAs("metamodel/archimate-3.2.yaml must be on the classpath")
                    .isNotNull();
            metamodel = MetamodelLoader.load(source);
        }
    }

    @Test
    @DisplayName("the whole language loads: 11 relationship types, 62 concepts, 25 viewpoints")
    void loads_the_complete_language() {
        assertThat(metamodel.language()).isEqualTo("archimate");
        assertThat(metamodel.version()).isEqualTo("3.2");
        assertThat(metamodel.relationshipTypes()).hasSize(11);
        assertThat(metamodel.elementTypes()).hasSize(62);
        assertThat(metamodel.viewpoints()).hasSize(25);
    }

    @Test
    @DisplayName("60 real element types, plus Junction and Relationship which are not elements")
    void separates_real_elements_from_matrix_endpoints() {
        assertThat(metamodel.elementTypes().stream().filter(ElementType::isPseudoElement))
                .extracting(ElementType::concept)
                .containsExactlyInAnyOrder("Junction", "Relationship");
        assertThat(metamodel.elementTypes().stream()
                        .filter(type -> !type.isPseudoElement())
                        .count())
                .isEqualTo(60);
    }

    @Test
    @DisplayName("layer and aspect survive the round trip")
    void classifies_element_types() {
        assertThat(metamodel.elementType("ApplicationComponent"))
                .contains(new ElementType("ApplicationComponent", "Application Component", Layer.APPLICATION, Aspect.ACTIVE_STRUCTURE));
        assertThat(metamodel.elementType("BusinessProcess")).get().satisfies(type -> {
            assertThat(type.layer()).isEqualTo(Layer.BUSINESS);
            assertThat(type.aspect()).isEqualTo(Aspect.BEHAVIOR);
        });
        assertThat(metamodel.elementType("Material")).get().satisfies(type -> {
            assertThat(type.layer()).isEqualTo(Layer.PHYSICAL);
            assertThat(type.aspect()).isEqualTo(Aspect.PASSIVE_STRUCTURE);
        });
        // Motivation elements sit outside the active/behaviour/passive classification.
        assertThat(metamodel.elementType("Goal")).get().satisfies(type -> {
            assertThat(type.layer()).isEqualTo(Layer.MOTIVATION);
            assertThat(type.aspect()).isEqualTo(Aspect.NONE);
        });
    }

    @Test
    @DisplayName("relationship categories and the structural ordering load")
    void classifies_relationship_types() {
        assertThat(metamodel.relationshipType("Composition")).get().satisfies(type -> {
            assertThat(type.category()).isEqualTo(RelationshipCategory.STRUCTURAL);
            assertThat(type.strength()).isEqualTo(1);
        });
        assertThat(metamodel.relationshipType("Serving")).get().satisfies(type -> {
            assertThat(type.category()).isEqualTo(RelationshipCategory.DEPENDENCY);
            assertThat(type.strength()).isEqualTo(RelationshipType.NOT_STRUCTURAL);
        });
        assertThat(metamodel.relationshipTypes().stream().filter(RelationshipType::isStructural))
                .extracting(RelationshipType::concept)
                .containsExactlyInAnyOrder("Composition", "Aggregation", "Assignment", "Realization");
    }

    @Test
    @DisplayName("the matrix answers the question the editor will ask on every drag")
    void validates_relationships() {
        assertThat(metamodel.check("ApplicationComponent", "ApplicationService", "Realization").isPermitted())
                .isTrue();
        assertThat(metamodel.check("ApplicationComponent", "DataObject", "Access").isPermitted())
                .isTrue();
        assertThat(metamodel.check("ApplicationComponent", "DataObject", "Serving").isPermitted())
                .isFalse();
    }

    @Test
    @DisplayName("a rejection names the alternatives instead of only saying no")
    void explains_rejections() {
        RelationshipVerdict verdict = metamodel.check("ApplicationComponent", "DataObject", "Serving");

        assertThat(verdict).isInstanceOf(RelationshipVerdict.NotPermitted.class);
        assertThat(verdict.explain())
                .contains("Serving is not permitted from Application Component to Data Object")
                .contains("Permitted instead:")
                .contains("Access")
                .contains("Association");
    }

    @Test
    @DisplayName("an unknown concept is reported as unknown, not as forbidden")
    void distinguishes_unknown_concepts_from_forbidden_ones() {
        RelationshipVerdict verdict = metamodel.check("Microservice", "DataObject", "Access");

        assertThat(verdict).isInstanceOf(RelationshipVerdict.UnknownConcept.class);
        assertThat(verdict.explain()).contains("'Microservice' is not a known source type");
    }

    @Test
    @DisplayName("viewpoint macros were expanded at generation time")
    void viewpoint_palettes_are_expanded() {
        Optional<Viewpoint> cooperation = metamodel.viewpoint("application_cooperation");
        assertThat(cooperation).isPresent();
        // Upstream lists two entries here, one of them the $ApplicationElements$ macro. An
        // unexpanded palette would be a two-item list and a silently broken editor.
        assertThat(cooperation.get().concepts()).hasSizeGreaterThan(5).contains("ApplicationComponent", "Location");

        Viewpoint layered = metamodel.viewpoint("layered").orElseThrow();
        assertThat(layered.allowsEveryConcept()).isTrue();
        assertThat(layered.allows("BusinessActor")).isTrue();
    }

    @Test
    @DisplayName("some relationship is permitted between every pair of real element types")
    void matrix_covers_every_pair_of_real_elements() {
        for (ElementType source : metamodel.elementTypes()) {
            for (ElementType target : metamodel.elementTypes()) {
                if (source.isPseudoElement() || target.isPseudoElement()) {
                    continue;
                }
                assertThat(metamodel.permittedBetween(source.concept(), target.concept()))
                        .describedAs("%s -> %s", source.concept(), target.concept())
                        .isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName("the only pairs permitting nothing are the three between the pseudo-concepts")
    void the_matrix_has_exactly_three_empty_cells() {
        var empty = metamodel.elementTypes().stream()
                .flatMap(source -> metamodel.elementTypes().stream()
                        .filter(target -> metamodel
                                .permittedBetween(source.concept(), target.concept())
                                .isEmpty())
                        .map(target -> source.concept() + " -> " + target.concept()))
                .toList();

        // Pinned rather than merely counted: a fourth empty cell appearing after an upstream
        // update means a pair of element types silently became unconnectable, and that is a
        // change to the language worth noticing.
        assertThat(empty)
                .containsExactlyInAnyOrder(
                        "Junction -> Relationship", "Relationship -> Junction", "Relationship -> Relationship");
    }
}
