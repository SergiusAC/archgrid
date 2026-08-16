plugins {
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "dev.archgrid"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencies {
        // BOM only — adds no artifacts, so archgrid-domain stays dependency-free.
        // Applied to the annotation-processor paths too: without it, processors declared
        // without a version cannot be resolved.
        "implementation"(platform(rootProject.libs.spring.boot.bom))
        "testImplementation"(platform(rootProject.libs.spring.boot.bom))
        "annotationProcessor"(platform(rootProject.libs.spring.boot.bom))
        "testAnnotationProcessor"(platform(rootProject.libs.spring.boot.bom))

        "implementation"("org.jspecify:jspecify")

        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf(
                "-parameters",
                "-Xlint:all,-serial,-this-escape,-processing,-requires-automatic",
                "-Werror",
            )
        )
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// Accessor for the version catalog inside `subprojects {}`, where type-safe `libs`
// is not available.
val org.gradle.api.Project.libs: org.gradle.accessors.dm.LibrariesForLibs
    get() = extensions.getByType()
