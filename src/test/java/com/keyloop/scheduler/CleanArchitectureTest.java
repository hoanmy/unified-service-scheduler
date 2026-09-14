package com.keyloop.scheduler;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchUnit architecture rule enforcement for Clean Architecture compliance.
 *
 * <p>Ensures that:
 * <ol>
 *   <li>Domain layer has ZERO framework dependencies (no Quarkus, no JPA annotations)</li>
 *   <li>Application layer depends only on domain (not infrastructure)</li>
 *   <li>Infrastructure depends on application ports (not use case implementations directly)</li>
 *   <li>Dependency inversion: infrastructure imports application ports, not vice versa</li>
 * </ol>
 *
 * <p>Run with: {@code ./mvnw test}
 */
@DisplayName("Clean Architecture — Dependency Rules")
class CleanArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
        .importPackages("com.keyloop.scheduler");

    @Test
    @DisplayName("Domain layer must not depend on any framework or infrastructure")
    void domainMustNotDependOnFrameworks() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "io.quarkus..",
                "jakarta.persistence..",
                "jakarta.inject..",
                "io.smallrye.mutiny..",
                "org.hibernate..",
                "..infrastructure.."
            )
            .because("Domain entities must be pure Java — no framework coupling");

        rule.check(classes);
    }

    @Test
    @DisplayName("Application layer must not depend on infrastructure layer")
    void applicationMustNotDependOnInfrastructure() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .because("Application use cases must depend only on domain and port interfaces");

        rule.check(classes);
    }

    @Test
    @DisplayName("Use case implementations must only implement port interfaces")
    void useCasesMustImplementPorts() {
        ArchRule rule = classes()
            .that().resideInAPackage("..application.usecase..")
            .should().implement(com.tngtech.archunit.base.DescribedPredicate.describe(
                "a port in interface in application.port.in",
                javaClass -> javaClass.getAllRawInterfaces().stream()
                    .anyMatch(i -> i.getPackageName().contains("application.port.in"))
            ))
            .because("Use case implementations must implement port interfaces");

        // Note: this rule validates the Clean Architecture contract
        rule.check(classes);
    }

    @Test
    @DisplayName("Layered architecture dependency flow: domain → application → infrastructure → web")
    void layeredArchitectureShouldBeRespected() {
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("..domain..")
            .layer("Application").definedBy("..application..")
            .layer("Infrastructure").definedBy("..infrastructure..")
            .whereLayer("Domain").mayNotAccessAnyLayer()
            .whereLayer("Application").mayOnlyAccessLayers("Domain")
            .whereLayer("Infrastructure").mayOnlyAccessLayers("Application", "Domain")
            .check(classes);
    }
}
