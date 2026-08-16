pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "archgrid"

// Gradle modules are deliberately few. Feature boundaries (metamodel, model, views,
// history, search, import, ai, admin) live in packages inside archgrid-app and are
// enforced by ArchUnit, not by the build. See docs/02-architecture.md §7.
//
// archgrid-domain exists as a separate module for one reason: to make the "domain has
// no Spring, no JPA, no Jackson" rule a compile-time guarantee rather than a convention.
include("archgrid-domain")
include("archgrid-app")
