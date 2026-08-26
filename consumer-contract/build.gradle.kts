import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.jvm) apply false
}

android {
    namespace = "at.bernhardberger.tvheadend.sdk.consumer"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "at.bernhardberger.tvheadend.sdk.consumer"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

val sdkVersion = Regex("""(?m)^version = "([^"]+)"[ \t]*$""")
    .findAll(file("../build.gradle.kts").readText())
    .singleOrNull()
    ?.groupValues
    ?.get(1)
    ?: error("The root project must declare exactly one literal version")

dependencies {
    implementation("at.bernhardberger.tvheadend:sdk-media3") {
        version { strictly(sdkVersion) }
    }
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

val sdkArtifactExtensions = mapOf(
    "sdk-android" to "aar",
    "sdk-core" to "jar",
    "sdk-media3" to "aar",
    "sdk-playback" to "jar",
    "sdk-testing" to "jar",
)
val stagedArtifactIdentity = sdkArtifactExtensions.mapValues { (module, extension) ->
    configurations.create("${module.replace("-", "")}StagedArtifact") {
        isCanBeConsumed = false
        isCanBeDeclared = true
        isCanBeResolved = true
        dependencies.add(
            project.dependencies.create(
                "at.bernhardberger.tvheadend:$module:$sdkVersion@$extension",
            ),
        )
    }
}

tasks.register("verifyConsumerDependencyGraph") {
    group = "verification"
    description = "Compiles a staged SDK consumer and verifies metadata, graph, and artifact identity."
    dependsOn("assembleDebug")

    doLast {
        val sdkGroup = "at.bernhardberger.tvheadend"
        val expectedSdkModules = setOf("sdk-core", "sdk-media3", "sdk-playback")
        val expectedSdkCoordinates = expectedSdkModules.mapTo(mutableSetOf()) { module ->
            "$sdkGroup:$module:$sdkVersion"
        }
        val implementationDependencies = configurations.getByName("implementation").dependencies
            .filterIsInstance<ExternalModuleDependency>()
        check(implementationDependencies.size == 1) {
            "The consumer must declare exactly one external dependency"
        }
        check(implementationDependencies.single().let { dependency ->
            dependency.group == sdkGroup &&
                dependency.name == "sdk-media3" &&
                dependency.versionConstraint.strictVersion == sdkVersion
        }) {
            "The consumer must depend strictly and directly on staged sdk-media3"
        }

        fun moduleIds(configurationName: String): Set<String> {
            val resolution = configurations.getByName(configurationName).incoming.resolutionResult
            val consumerRoot = resolution.rootComponent.get().id
            return resolution.allComponents
                .mapNotNull { component ->
                    when (val identifier = component.id) {
                        is ModuleComponentIdentifier ->
                            "${identifier.group}:${identifier.module}:${identifier.version}"
                        is ProjectComponentIdentifier -> {
                            check(identifier == consumerRoot) {
                                "The staged consumer resolved a project component: ${identifier.projectPath}"
                            }
                            null
                        }
                        else -> null
                    }
                }
                .toSet()
        }

        val compileModules = moduleIds("debugCompileClasspath")
        val runtimeModules = moduleIds("debugRuntimeClasspath")
        val compileSdkCoordinates = compileModules
            .filter { coordinate -> coordinate.startsWith("$sdkGroup:sdk-") }
            .toSet()
        val runtimeSdkCoordinates = runtimeModules
            .filter { coordinate -> coordinate.startsWith("$sdkGroup:sdk-") }
            .toSet()
        check(compileSdkCoordinates == expectedSdkCoordinates) {
            "Unexpected staged SDK compile graph: $compileSdkCoordinates"
        }
        check(runtimeSdkCoordinates == expectedSdkCoordinates) {
            "Unexpected staged SDK runtime graph: $runtimeSdkCoordinates"
        }
        check(compileModules.none { coordinate -> coordinate.startsWith("$sdkGroup:htsp:") }) {
            "Raw HTSP must not be exposed on the consumer compile classpath"
        }
        check(runtimeModules.contains("$sdkGroup:htsp:${libs.versions.htsp.get()}")) {
            "The staged runtime graph must retain the SDK's HTSP implementation dependency"
        }

        sdkArtifactExtensions.keys.forEach { module ->
            val identityConfiguration = stagedArtifactIdentity.getValue(module)
            val identityCoordinate = identityConfiguration.incoming.resolutionResult.allComponents
                .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
                .single()
            check(
                "${identityCoordinate.group}:${identityCoordinate.module}:${identityCoordinate.version}" ==
                    "$sdkGroup:$module:$sdkVersion",
            ) {
                "The staged identity configuration resolved an unexpected coordinate: $identityCoordinate"
            }
            val resolved = identityConfiguration.singleFile
            val extension = sdkArtifactExtensions.getValue(module)
            val stagedDirectory = rootDir.resolve(
                "../build/local-maven/${sdkGroup.replace('.', '/')}/$module/$sdkVersion",
            )
            val candidates = if (sdkVersion.endsWith("-SNAPSHOT")) {
                val baseVersion = sdkVersion.removeSuffix("-SNAPSHOT")
                val artifactName = Regex(
                    "${Regex.escape(module)}-${Regex.escape(baseVersion)}-" +
                        "\\d{8}\\.\\d{6}-\\d+\\.${Regex.escape(extension)}",
                )
                stagedDirectory.listFiles().orEmpty().filter { file -> artifactName.matches(file.name) }
            } else {
                listOf(stagedDirectory.resolve("$module-$sdkVersion.$extension"))
            }
            val staged = candidates
                .filter { candidate ->
                    candidate.isFile && resolved.readBytes().contentEquals(candidate.readBytes())
                }
                .maxByOrNull { candidate -> candidate.name }
                ?: error("The resolved $module bytes do not match one staged artifact")
            val stagedDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(staged.readBytes()),
            )
            val sidecar = staged.parentFile.resolve("${staged.name}.sha256")
            check(sidecar.isFile && sidecar.readText().trim() == stagedDigest) {
                "The staged $module checksum differs from its sidecar"
            }
        }

        val consumerSource = fileTree("src/main/kotlin") { include("**/*.kt") }
            .files.joinToString("\n") { source -> source.readText() }
        check("at.bernhardberger.tvheadend.htsp" !in consumerSource) {
            "The consumer contract must not import the raw HTSP API"
        }
        check("SubscriptionInfrastructureApi" !in consumerSource) {
            "Application consumers must not require the SDK infrastructure opt-in"
        }
    }
}

tasks.named("check") {
    dependsOn("verifyConsumerDependencyGraph")
}
