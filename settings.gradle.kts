pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tvheadend-sdk"

include(
    ":sdk-android",
    ":sdk-core",
    ":sdk-media3",
    ":sdk-playback",
    ":sdk-testing",
)

val useHtspComposite = providers.gradleProperty("tvheadend.htsp.composite")
    .map(String::toBooleanStrict)
    .getOrElse(false)

if (useHtspComposite) {
    val htspCheckout = file("../tvheadend-htsp").canonicalFile
    check(htspCheckout.resolve("settings.gradle.kts").isFile) {
        "The opt-in HTSP composite checkout is unavailable"
    }
    includeBuild(htspCheckout) {
        dependencySubstitution {
            substitute(module("at.bernhardberger.tvheadend:htsp"))
                .using(project(":"))
        }
    }
}
