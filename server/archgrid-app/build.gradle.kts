plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":archgrid-domain"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // The starter, not org.flywaydb:flyway-core alone. Boot 4 moved auto-configuration out
    // of spring-boot-autoconfigure into per-technology modules, so the bare library gives
    // the Flyway classes but nothing that runs migrations at startup.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    implementation(libs.springdoc.openapi)

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 split test auto-configuration into per-technology modules; MockMvc support
    // is no longer part of spring-boot-test-autoconfigure.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    // Testcontainers 2.x renamed every module to a testcontainers-* prefix.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.archunit)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "archgrid.jar"
}

// The canonical metamodel lives at the repository root next to the upstream sources it is
// generated from, not buried in src/main/resources — it is a reviewable artefact, not an
// implementation detail. It is copied onto the classpath so the running application and the
// air-gapped distribution both carry it.
tasks.named<ProcessResources>("processResources") {
    from(rootDir.parentFile.resolve("metamodel")) {
        include("archimate-3.2.yaml")
        include("c4-mapping.yaml")
        into("metamodel")
    }
}

// Rewrites metamodel/archimate-3.2.yaml from the vendored Archi sources. Kept out of the
// normal build on purpose: regenerating is a deliberate act whose diff must be read, not a
// side effect of compiling. MetamodelGenerationTest guarantees the committed file matches.
tasks.register<JavaExec>("generateMetamodel") {
    group = "archgrid"
    description = "Regenerate metamodel/archimate-3.2.yaml from metamodel/upstream/archi."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.archgrid.metamodel.upstream.ArchiMetamodelGenerator"
}
