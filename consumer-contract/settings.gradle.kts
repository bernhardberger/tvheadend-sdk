pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val checkoutLocalRepository = file("../build/local-maven")
            .toPath().toAbsolutePath().normalize()
        check(checkoutLocalRepository.toFile().isDirectory) {
            "Staged SDK repository is missing: $checkoutLocalRepository"
        }
        val expectedRepository = rootDir.parentFile.toPath().toRealPath().resolve("build/local-maven")
        check(checkoutLocalRepository.toRealPath() == expectedRepository) {
            "Staged SDK repository must not traverse a symlink or escape the checkout"
        }
        exclusiveContent {
            forRepository {
                maven {
                    name = "sdkCheckoutLocal"
                    url = checkoutLocalRepository.toUri()
                    content {
                        listOf("sdk-android", "sdk-core", "sdk-media3", "sdk-playback", "sdk-testing")
                            .forEach { module -> includeModule("at.bernhardberger.tvheadend", module) }
                    }
                }
            }
            filter {
                listOf("sdk-android", "sdk-core", "sdk-media3", "sdk-playback", "sdk-testing")
                    .forEach { module -> includeModule("at.bernhardberger.tvheadend", module) }
            }
        }
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "sdk-consumer-contract"
