package io.schnappy.chat;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "io.schnappy.chat", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    // -------------------------------------------------------------------------
    // Layering: services must not depend on controllers or websocket layer
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule services_must_not_depend_on_controllers =
            noClasses()
                    .that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..")
                    .as("Services must not depend on controllers");

    @ArchTest
    static final ArchRule services_must_not_depend_on_websocket =
            noClasses()
                    .that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().resideInAPackage("..websocket..")
                    .as("Services must not depend on the websocket layer");

    // -------------------------------------------------------------------------
    // Layering: kafka consumers/producers sit at the service layer —
    // they may use repositories and services but not controllers
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule kafka_must_not_depend_on_controllers =
            noClasses()
                    .that().resideInAPackage("..kafka..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..")
                    .as("Kafka classes must not depend on controllers");

    // -------------------------------------------------------------------------
    // Layering: repositories must not depend on services, controllers, or kafka
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule repositories_must_not_depend_on_services =
            noClasses()
                    .that().resideInAPackage("..repository..")
                    .should().dependOnClassesThat().resideInAPackage("..service..")
                    .as("Repositories must not depend on services");

    @ArchTest
    static final ArchRule repositories_must_not_depend_on_controllers =
            noClasses()
                    .that().resideInAPackage("..repository..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..")
                    .as("Repositories must not depend on controllers");

    @ArchTest
    static final ArchRule repositories_must_not_depend_on_kafka =
            noClasses()
                    .that().resideInAPackage("..repository..")
                    .should().dependOnClassesThat().resideInAPackage("..kafka..")
                    .as("Repositories must not depend on kafka classes");

    // -------------------------------------------------------------------------
    // Entities must not depend on services or controllers
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule entities_must_not_depend_on_services =
            noClasses()
                    .that().resideInAPackage("..entity..")
                    .should().dependOnClassesThat().resideInAPackage("..service..")
                    .as("Entities must not depend on services");

    @ArchTest
    static final ArchRule entities_must_not_depend_on_controllers =
            noClasses()
                    .that().resideInAPackage("..entity..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..")
                    .as("Entities must not depend on controllers");

    // -------------------------------------------------------------------------
    // Security isolation
    //
    // GatewayAuthFilter is intentionally allowed to depend on UserCacheService:
    // it populates the user identity cache on each authenticated request so
    // downstream services can resolve usernames from IDs without extra DB hits.
    // All other security classes must remain independent of business logic.
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule security_must_not_depend_on_controllers =
            noClasses()
                    .that().resideInAPackage("..security..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..")
                    .as("Security classes must not depend on controllers");

    @ArchTest
    static final ArchRule security_must_not_depend_on_services =
            noClasses()
                    .that().resideInAPackage("..security..")
                    .and().doNotHaveSimpleName("GatewayAuthFilter")
                    .should().dependOnClassesThat().resideInAPackage("..service..")
                    .as("Security classes (except GatewayAuthFilter) must not depend on services");

    @ArchTest
    static final ArchRule security_must_not_depend_on_repositories =
            noClasses()
                    .that().resideInAPackage("..security..")
                    .should().dependOnClassesThat().resideInAPackage("..repository..")
                    .as("Security classes must not depend on repositories");

    // -------------------------------------------------------------------------
    // GatewayAuthFilter must reside in the security package
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule gateway_auth_filter_in_security_package =
            classes()
                    .that().haveSimpleName("GatewayAuthFilter")
                    .should().resideInAPackage("..security..")
                    .as("GatewayAuthFilter must reside in the security package");

    // -------------------------------------------------------------------------
    // No package cycles
    //
    // The service ↔ kafka cycle is an intentional architectural trade-off:
    // services (ChatService, SystemChannelService) depend on ChatKafkaProducer
    // to publish messages, while kafka consumers (UserEventConsumer) depend on
    // services to handle incoming events. These two directions are kept in
    // separate classes and the cycle is accepted. All other package pairs must
    // remain acyclic.
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule no_cycles_in_chat_packages =
            slices()
                    .matching("io.schnappy.chat.(*)..")
                    .namingSlices("Slice $1")
                    .should().beFreeOfCycles()
                    .ignoreDependency(
                            resideInAPackage("..kafka.."),
                            resideInAPackage("..service.."))
                    .ignoreDependency(
                            resideInAPackage("..service.."),
                            resideInAPackage("..kafka.."))
                    .as("Packages under io.schnappy.chat must be free of cycles (excluding the known service<->kafka co-dependency)");
}
