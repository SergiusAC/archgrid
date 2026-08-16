package dev.archgrid.metamodel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Layer;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.c4.C4ElementRule;
import dev.archgrid.domain.metamodel.c4.C4Level;
import dev.archgrid.domain.metamodel.c4.C4Mapping;
import dev.archgrid.domain.metamodel.c4.C4RelationshipIntent;
import dev.archgrid.domain.metamodel.c4.C4Shape;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Loads the real C4 mapping against the real metamodel and checks what it actually says. */
class C4MappingLoaderTest {

    private static Metamodel metamodel;
    private static C4Mapping mapping;

    @BeforeAll
    static void loadFromClasspath() throws IOException {
        try (InputStream archimate =
                C4MappingLoaderTest.class.getResourceAsStream("/metamodel/archimate-3.2.yaml")) {
            metamodel = MetamodelLoader.load(archimate);
        }
        try (InputStream c4 = C4MappingLoaderTest.class.getResourceAsStream("/metamodel/c4-mapping.yaml")) {
            assertThat(c4).describedAs("metamodel/c4-mapping.yaml must be on the classpath").isNotNull();
            mapping = C4MappingLoader.load(c4, metamodel);
        }
    }

    @Test
    @DisplayName("people, boxes and boundaries are mapped; everything else is omitted")
    void renders_only_what_c4_can_express() {
        assertThat(mapping.shapeOf("BusinessActor")).isEqualTo(C4Shape.PERSON);
        assertThat(mapping.shapeOf("ApplicationComponent")).isEqualTo(C4Shape.BOX);
        assertThat(mapping.shapeOf("SystemSoftware")).isEqualTo(C4Shape.BOX);
        assertThat(mapping.shapeOf("Grouping")).isEqualTo(C4Shape.BOUNDARY);

        // C4 has no behaviour, no interfaces, no motivation and no strategy. Saying so
        // explicitly is the point: a view reports what it does not show.
        assertThat(mapping.shapeOf("ApplicationService")).isEqualTo(C4Shape.OMITTED);
        assertThat(mapping.shapeOf("ApplicationInterface")).isEqualTo(C4Shape.OMITTED);
        assertThat(mapping.shapeOf("BusinessProcess")).isEqualTo(C4Shape.OMITTED);
        assertThat(mapping.shapeOf("Goal")).isEqualTo(C4Shape.OMITTED);
        assertThat(mapping.shapeOf("Capability")).isEqualTo(C4Shape.OMITTED);
        assertThat(mapping.shapeOf("WorkPackage")).isEqualTo(C4Shape.OMITTED);
    }

    @Test
    @DisplayName("most of ArchiMate is not representable in C4, and that is expected")
    void the_representable_subset_is_small() {
        long representable = metamodel.elementTypes().stream()
                .filter(type -> mapping.shapeOf(type) != C4Shape.OMITTED)
                .count();

        // Pinned deliberately. If this number moves, someone widened or narrowed what C4 shows,
        // and that is a design change that belongs in ADR-0007 rather than in a quiet commit.
        assertThat(representable).isEqualTo(10);
    }

    @Test
    @DisplayName("technology is kept off the context level")
    void levels_filter_by_layer() {
        C4Level context = mapping.level("context").orElseThrow();
        C4Level container = mapping.level("container").orElseThrow();

        ElementType component = metamodel.elementType("ApplicationComponent").orElseThrow();
        ElementType systemSoftware = metamodel.elementType("SystemSoftware").orElseThrow();

        assertThat(mapping.isVisibleAt(component, context)).isTrue();
        assertThat(mapping.isVisibleAt(systemSoftware, context))
                .describedAs("technology on a system context diagram is how it stops being one")
                .isFalse();
        assertThat(mapping.isVisibleAt(systemSoftware, container)).isTrue();

        assertThat(context.depth()).isZero();
        assertThat(container.depth()).isEqualTo(1);
        assertThat(context.admits(Layer.TECHNOLOGY)).isFalse();
    }

    @Test
    @DisplayName("a C4 container resolves by technology, not by wishful default")
    void container_authoring_discriminates_on_technology() {
        C4ElementRule container = mapping.authoringRule("container").orElseThrow();

        assertThat(container.resolve("Spring Boot").concept()).isEqualTo("ApplicationComponent");
        assertThat(container.resolve(null).concept()).isEqualTo("ApplicationComponent");
        assertThat(container.resolve("PostgreSQL 16").concept()).isEqualTo("SystemSoftware");
        assertThat(container.resolve("Apache Kafka").concept()).isEqualTo("SystemSoftware");
        assertThat(container.resolve("Amazon S3 object storage").concept()).isEqualTo("Artifact");
    }

    @Test
    @DisplayName("a C4 'uses' arrow becomes an ArchiMate Serving pointing the other way")
    void uses_reverses_direction() {
        C4RelationshipIntent uses = mapping.defaultIntent();

        assertThat(uses.intent()).isEqualTo("uses");
        assertThat(uses.relationship().concept()).isEqualTo("Serving");
        assertThat(uses.reverse()).isTrue();

        // C4: Notification Service --uses--> Kafka.  ArchiMate: Kafka --serves--> Notification Service.
        C4RelationshipIntent.Directed directed = uses.apply("NotificationService", "Kafka");
        assertThat(directed.source()).isEqualTo("Kafka");
        assertThat(directed.target()).isEqualTo("NotificationService");
    }

    @Test
    @DisplayName("flow, triggering and access keep C4's direction")
    void other_intents_do_not_reverse() {
        C4RelationshipIntent sends = mapping.intent("sends").orElseThrow();
        assertThat(sends.relationship().concept()).isEqualTo("Flow");
        assertThat(sends.apply("PaymentService", "Kafka").source()).isEqualTo("PaymentService");

        assertThat(mapping.intent("triggers").orElseThrow().reverse()).isFalse();
        assertThat(mapping.intent("accesses").orElseThrow().reverse()).isFalse();
    }

    @Test
    @DisplayName("every relationship the mapping produces is legal in the metamodel it maps")
    void authored_relationships_are_permitted_by_the_metamodel() {
        // The mapping could otherwise describe an import that produces models its own
        // validator rejects. Checked on the concrete pairs the mapping can actually create.
        assertThat(metamodel
                        .check("SystemSoftware", "ApplicationComponent", "Serving")
                        .isPermitted())
                .isTrue();
        assertThat(metamodel
                        .check("ApplicationComponent", "SystemSoftware", "Flow")
                        .isPermitted())
                .isTrue();
        assertThat(metamodel
                        .check("ApplicationComponent", "ApplicationComponent", "Triggering")
                        .isPermitted())
                .isTrue();
        assertThat(metamodel.check("ApplicationComponent", "DataObject", "Access").isPermitted())
                .isTrue();
    }
}
