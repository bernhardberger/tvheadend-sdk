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
        providers.gradleProperty("tvheadend.htsp.candidateRepository").orNull?.let { repositoryPath ->
            val candidateRepository = file(repositoryPath)
            check(candidateRepository.isDirectory) { "The opt-in HTSP candidate repository is unavailable" }
            exclusiveContent {
                forRepository {
                    maven {
                        name = "htspCandidate"
                        url = candidateRepository.toURI()
                    }
                }
                filter { includeModule("at.bernhardberger.tvheadend", "htsp") }
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
