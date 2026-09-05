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
    implementation("at.bernhardberger.tvheadend:sdk-android") {
        version { strictly(sdkVersion) }
    }
    implementation("at.bernhardberger.tvheadend:sdk-media3") {
        version { strictly(sdkVersion) }
    }
    implementation("at.bernhardberger.tvheadend:sdk-testing") {
        version { strictly(sdkVersion) }
    }
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
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
        val expectedSdkModules = setOf(
            "sdk-android",
            "sdk-core",
            "sdk-media3",
            "sdk-playback",
            "sdk-testing",
        )
        val expectedSdkCoordinates = expectedSdkModules.mapTo(mutableSetOf()) { module ->
            "$sdkGroup:$module:$sdkVersion"
        }
        val implementationDependencies = configurations.getByName("implementation").dependencies
            .filterIsInstance<ExternalModuleDependency>()
        check(implementationDependencies.size == 3) {
            "The consumer must declare exactly three external dependencies"
        }
        check(
            implementationDependencies.mapTo(mutableSetOf()) { dependency ->
                check(dependency.group == sdkGroup && dependency.versionConstraint.strictVersion == sdkVersion)
                dependency.name
            } == setOf("sdk-android", "sdk-media3", "sdk-testing"),
        ) {
            "The consumer must depend strictly and directly on staged Android, Media3, and testing SDK artifacts"
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
            val staged = stagedDirectory.resolve("$module-$sdkVersion.$extension")
            check(staged.isFile && resolved.readBytes().contentEquals(staged.readBytes())) {
                "The resolved $module bytes do not match the staged artifact"
            }
        }

        val consumerSource = fileTree("src/main") { include("**/*.kt", "**/*.java") }
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
