pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Optional local Maven mirror.
        providers.environmentVariable("DUO18_MAVEN_CACHE").orNull?.let { maven { url = uri(it) } }
        google(); mavenCentral()
    }
}
rootProject.name = "18Duo"
include(":app")

