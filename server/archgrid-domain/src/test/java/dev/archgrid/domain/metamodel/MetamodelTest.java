package dev.archgrid.domain.metamodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rules of the metamodel itself, exercised on a hand-built miniature rather than the real
 * 62-concept file, so that a failure points at the rule and not at the data.
 */
class MetamodelTest {

    private static final RelationshipType COMPOSITION =
            new RelationshipType("Composition", "Composition", 'c', RelationshipCategory.STRUCTURAL, 1);
    private static final RelationshipType ASSIGNMENT =
            new RelationshipType("Assignment", "Assignment", 'i', RelationshipCategory.STRUCTURAL, 3);
    private static final RelationshipType SERVING =
            new RelationshipType("Serving", "Serving", 'v', RelationshipCategory.DEPENDENCY, 0);

    private static final ElementType COMPONENT =
            new ElementType("ApplicationComponent", "Application Component", Layer.APPLICATION, Aspect.ACTIVE_STRUCTURE);
    private static final ElementType SERVICE =
            new ElementType("ApplicationService", "Application Service", Layer.APPLICATION, Aspect.BEHAVIOR);

    private static Metamodel.Builder minimal() {
        return Metamodel.builder()
                .version("3.2")
                .relationshipType(COMPOSITION)
                .relationshipType(ASSIGNMENT)
                .relationshipType(SERVING)
                .elementType(COMPONENT)
                .elementType(SERVICE);
    }

    @Test
    void permits_what_the_matrix_declares() {
        Metamodel metamodel = minimal()
                .permit("ApplicationComponent", "ApplicationService", List.of(ASSIGNMENT, SERVING))
                .build();

        assertThat(metamodel.check("ApplicationComponent", "ApplicationService", "Assignment").isPermitted())
                .isTrue();
        assertThat(metamodel.check("ApplicationComponent", "ApplicationService", "Composition").isPermitted())
                .isFalse();
    }

    @Test
    @DisplayName("a rejection carries the alternatives, which is what makes the message useful")
    void rejection_lists_alternatives() {
        Metamodel metamodel = minimal()
                .permit("ApplicationComponent", "ApplicationService", List.of(ASSIGNMENT, SERVING))
                .build();

        RelationshipVerdict verdict =
                metamodel.check("ApplicationComponent", "ApplicationService", "Composition");

        assertThat(verdict).isInstanceOf(RelationshipVerdict.NotPermitted.class);
        assertThat(((RelationshipVerdict.NotPermitted) verdict).alternatives())
                .containsExactly(ASSIGNMENT, SERVING);
        assertThat(verdict.explain()).contains("Permitted instead: Assignment, Serving.");
    }

    @Test
    @DisplayName("a pair with no permitted relationship says so rather than listing nothing")
    void rejection_without_alternatives_reads_sensibly() {
        Metamodel metamodel = minimal().build();

        assertThat(metamodel.check("ApplicationComponent", "ApplicationService", "Serving").explain())
                .contains("No relationship type is permitted between these two.");
    }

    @Test
    void reports_each_unknown_concept_in_its_own_role() {
        Metamodel metamodel = minimal().build();

        assertThat(metamodel.check("Nope", "ApplicationService", "Serving"))
                .isEqualTo(new RelationshipVerdict.UnknownConcept("Nope", RelationshipVerdict.UnknownConcept.Role.SOURCE));
        assertThat(metamodel.check("ApplicationComponent", "Nope", "Serving"))
                .isEqualTo(new RelationshipVerdict.UnknownConcept("Nope", RelationshipVerdict.UnknownConcept.Role.TARGET));
        assertThat(metamodel.check("ApplicationComponent", "ApplicationService", "Nope"))
                .isEqualTo(new RelationshipVerdict.UnknownConcept(
                        "Nope", RelationshipVerdict.UnknownConcept.Role.RELATIONSHIP));
    }

    @Test
    @DisplayName("a matrix row referencing an undeclared concept fails the build, loudly")
    void rejects_dangling_matrix_references() {
        assertThatThrownBy(() -> minimal()
                        .permit("ApplicationComponent", "Ghost", List.of(SERVING))
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ghost")
                .hasMessageContaining("permitted-relationship target");
    }

    @Test
    void rejects_viewpoints_referencing_undeclared_concepts() {
        assertThatThrownBy(() -> minimal()
                        .viewpoint(new Viewpoint("ghosts", "Ghosts", Set.of("Ghost")))
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ghost");
    }

    @Test
    @DisplayName("structural types must have distinct strengths or the derivation rule is ambiguous")
    void rejects_ambiguous_structural_ordering() {
        assertThatThrownBy(() -> Metamodel.builder()
                        .elementType(COMPONENT)
                        .relationshipType(COMPOSITION)
                        .relationshipType(new RelationshipType("Aggregation", "Aggregation", 'g', RelationshipCategory.STRUCTURAL, 1))
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distinct strengths");
    }

    @Test
    void rejects_duplicate_declarations() {
        assertThatThrownBy(() -> minimal().elementType(COMPONENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate element type");
    }

    @Test
    @DisplayName("the weakest link of a structural chain is the derived relationship")
    void weakest_link_rule() {
        assertThat(RelationshipType.weakerOf(COMPOSITION, ASSIGNMENT)).isEqualTo(ASSIGNMENT);
        assertThat(RelationshipType.weakerOf(ASSIGNMENT, COMPOSITION)).isEqualTo(ASSIGNMENT);
        assertThat(RelationshipType.weakerOf(COMPOSITION, COMPOSITION)).isEqualTo(COMPOSITION);
    }

    @Test
    @DisplayName("deriving across a non-structural link throws rather than guessing")
    void weakest_link_rule_refuses_non_structural_types() {
        assertThatThrownBy(() -> RelationshipType.weakerOf(COMPOSITION, SERVING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("structural relationships only");
    }

    @Test
    void relationship_type_rejects_inconsistent_strength() {
        assertThatThrownBy(() -> new RelationshipType("Serving", "Serving", 'v', RelationshipCategory.DEPENDENCY, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RelationshipType("Composition", "Composition", 'c', RelationshipCategory.STRUCTURAL, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
