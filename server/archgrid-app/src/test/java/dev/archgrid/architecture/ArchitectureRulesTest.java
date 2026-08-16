package dev.archgrid.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Executable form of the module boundaries described in docs/02-architecture.md §7.
 *
 * <p>These rules exist because architectural boundaries that live only in a document erode
 * within weeks. Written before the code they govern, they are cheap; retrofitted onto a
 * grown codebase, they are a rewrite.
 */
class ArchitectureRulesTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.archgrid");

    @Test
    @DisplayName("the domain depends on nothing but the JDK")
    void domain_is_free_of_frameworks() {
        noClasses()
                .that()
                .resideInAPackage("dev.archgrid.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta..",
                        "javax..",
                        "com.fasterxml.jackson..",
                        "tools.jackson..",
                        "java.sql..",
                        "org.flywaydb..",
                        "org.slf4j..")
                .because("the metamodel, the graph and command validation must be testable "
                        + "without a Spring context and without a database — see ADR-0002")
                .check(CLASSES);
    }

    @Test
    @DisplayName("the domain does not know about the application that hosts it")
    void domain_does_not_depend_on_adapters() {
        noClasses()
                .that()
                .resideInAPackage("dev.archgrid.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("dev.archgrid.platform..", "dev.archgrid.admin..")
                .because("dependencies point inward: adapters know the domain, never the reverse")
                .check(CLASSES);
    }

    @Test
    @DisplayName("controllers go through a service, never straight to persistence")
    void controllers_do_not_reach_repositories() {
        noClasses()
                .that()
                .haveSimpleNameEndingWith("Controller")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository")
                .because("from phase 2 the only legal way to change a model is a validated "
                        + "command, and a controller that can reach a repository can bypass it")
                .check(CLASSES);
    }

    @Test
    @DisplayName("no field injection")
    void dependencies_are_injected_through_constructors() {
        noFields()
                .should()
                .beAnnotatedWith(Autowired.class)
                .because("constructor injection keeps collaborators explicit and objects "
                        + "constructible in a plain unit test")
                .check(CLASSES);
    }

    @Test
    @DisplayName("feature slices form no dependency cycles")
    void feature_slices_are_acyclic() {
        SlicesRuleDefinition.slices()
                .matching("dev.archgrid.(*)..")
                .should()
                .beFreeOfCycles()
                .because("the roadmap adds metamodel, model, views, history, search, import "
                        + "and ai as slices; a cycle between any two makes all of them "
                        + "untestable in isolation")
                .check(CLASSES);
    }
}
