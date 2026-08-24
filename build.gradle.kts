import com.android.Version
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    base
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dokka) apply false
}

group = "at.bernhardberger.tvheadend"
version = "0.1.0-SNAPSHOT"

val sdkModules = setOf(
    "sdk-android",
    "sdk-core",
    "sdk-media3",
    "sdk-playback",
    "sdk-testing",
)
val androidModules = setOf("sdk-android", "sdk-media3")
val publicationDescriptions = mapOf(
    "sdk-android" to "Android discovery, connectivity, credentials, and artwork integration for TVHeadend.",
    "sdk-core" to "TVHeadend SDK protocol integration, lifecycle, models, metadata, EPG, and DVR workflows.",
    "sdk-media3" to "Android Media3 playback integration for the TVHeadend SDK.",
    "sdk-playback" to "TVHeadend subscription, seek, timeshift, and timestamp state machines.",
    "sdk-testing" to "JVM test fakes, scripted events, repositories, and packet fixtures for the TVHeadend SDK.",
)

check(subprojects.map(Project::getName).toSet() == sdkModules) {
    "The SDK build must contain exactly $sdkModules"
}

allprojects {
    group = rootProject.group
    version = rootProject.version
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val requiredVersions = mapOf(
    "agp" to "9.3.1",
    "androidxTestExtJunit" to "1.3.0",
    "androidxTestRunner" to "1.7.0",
    "buildTools" to "36.0.0",
    "compileSdk" to "36",
    "coroutines" to "1.10.2",
    "dataStore" to "1.2.1",
    "detekt" to "2.0.0-alpha.6",
    "dokka" to "2.2.0",
    "foojay" to "1.0.0",
    "htsp" to "0.6.0",
    "jdk" to "21",
    "junit" to "6.1.3",
    "jvmTarget" to "17",
    "konsist" to "0.17.3",
    "kotlin" to "2.4.10",
    "media3" to "1.11.0",
    "minSdk" to "24",
    "targetSdk" to "36",
    "tink" to "1.23.0",
    "turbine" to "1.2.1",
)
val selectedVersions = requiredVersions.keys.associateWith { alias ->
    versionCatalog.findVersion(alias).orElseThrow().requiredVersion
}
val builtInKotlinProperty = providers.gradleProperty("android.builtInKotlin").orNull
val newDslProperty = providers.gradleProperty("android.newDsl").orNull

val verifyBuildMatrix = tasks.register("verifyBuildMatrix") {
    group = "verification"
    description = "Checks the exact Gradle, Kotlin, Android, JVM, and dependency version matrix."
    inputs.property("requiredVersions", requiredVersions)
    inputs.property("selectedVersions", selectedVersions)
    inputs.property("androidBuiltInKotlin", builtInKotlinProperty ?: "<unset>")
    inputs.property("androidNewDsl", newDslProperty ?: "<unset>")
    inputs.property("runningGradleVersion", gradle.gradleVersion)
    inputs.property("runningJavaVersion", JavaVersion.current().majorVersion)
    inputs.property("loadedAgpVersion", Version.ANDROID_GRADLE_PLUGIN_VERSION)
    doLast {
        val values = inputs.properties
        val required = (values.getValue("requiredVersions") as Map<*, *>)
            .map { (key, value) -> key.toString() to value.toString() }
            .toMap()
        val selected = (values.getValue("selectedVersions") as Map<*, *>)
            .map { (key, value) -> key.toString() to value.toString() }
            .toMap()
        val runningGradleVersion = values.getValue("runningGradleVersion") as String
        val runningJavaVersion = values.getValue("runningJavaVersion") as String
        val loadedAgpVersion = values.getValue("loadedAgpVersion") as String
        val loadedKotlinPluginVersion = values.getValue("loadedKotlinPluginVersion") as String

        check(runningGradleVersion == "9.7.0") {
            "Gradle $runningGradleVersion is running; expected 9.7.0"
        }
        check(runningJavaVersion == "21") {
            "The build must run on JDK 21, found $runningJavaVersion"
        }
        check(loadedAgpVersion == "9.3.1") {
            "AGP $loadedAgpVersion is loaded; expected 9.3.1"
        }
        check(loadedKotlinPluginVersion == "2.4.10") {
            "Kotlin Gradle plugin $loadedKotlinPluginVersion is loaded; expected 2.4.10"
        }
        check(selected == required) {
            "The selected version matrix differs from the required matrix: $selected"
        }
        check(values.getValue("androidBuiltInKotlin") in setOf("<unset>", "true")) {
            "AGP built-in Kotlin must remain enabled"
        }
        check(values.getValue("androidNewDsl") in setOf("<unset>", "true")) {
            "AGP's current DSL must remain enabled"
        }

        listOf("sdk-android", "sdk-media3").forEach { moduleName ->
            check(values.getValue("$moduleName.kotlinAndroidApplied") == false) {
                "$moduleName must use AGP built-in Kotlin"
            }
            check(values.getValue("$moduleName.compileSdk") == 36)
            check(values.getValue("$moduleName.buildTools") == "36.0.0")
            check(values.getValue("$moduleName.minSdk") == 24)
            check(values.getValue("$moduleName.targetSdk") == 36)
            check(
                values.getValue("$moduleName.testRunner") ==
                    "androidx.test.runner.AndroidJUnitRunner",
            )
        }
    }
}

data class ProductionGraph(
    val direct: Set<String>,
    val resolved: Set<String>,
)

val commonDirect = emptySet<String>()
val commonResolved = setOf(
    "org.jetbrains:annotations:13.0",
    "org.jetbrains.kotlin:kotlin-stdlib:2.4.10",
)
val coreResolved = (commonResolved - "org.jetbrains:annotations:13.0") + setOf(
    "at.bernhardberger.tvheadend:htsp:0.6.0",
    "org.jetbrains:annotations:23.0.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2",
)
val coroutineResolved = (coreResolved - "at.bernhardberger.tvheadend:htsp:0.6.0")
val useHtspComposite = providers.gradleProperty("tvheadend.htsp.composite")
    .map(String::toBooleanStrict)
    .getOrElse(false)
val productionGraphs = sdkModules.associateWith {
    ProductionGraph(commonDirect, commonResolved)
}.toMutableMap().apply {
    androidModules.forEach { moduleName ->
        this[moduleName] = ProductionGraph(
            direct = commonDirect,
            resolved = commonResolved,
        )
    }
    this["sdk-core"] = ProductionGraph(
        direct = commonDirect + setOf(
            "project::sdk-playback",
            "at.bernhardberger.tvheadend:htsp:0.6.0",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
        ),
        resolved = (if (useHtspComposite) {
            (coreResolved - "at.bernhardberger.tvheadend:htsp:0.6.0") + "project::tvheadend-htsp"
        } else {
            coreResolved
        }) + "project::sdk-playback",
    )
    this["sdk-android"] = ProductionGraph(
        direct = setOf(
            "androidx.datastore:datastore-preferences:1.2.1",
            "com.google.crypto.tink:tink-android:1.23.0",
            "project::sdk-core",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
        ),
        resolved = (if (useHtspComposite) {
            (coreResolved - "at.bernhardberger.tvheadend:htsp:0.6.0") +
                "project::tvheadend-htsp"
        } else {
            coreResolved
        }) + setOf(
            "androidx.annotation:annotation-jvm:1.9.1",
            "androidx.annotation:annotation:1.9.1",
            "androidx.datastore:datastore-android:1.2.1",
            "androidx.datastore:datastore-core-android:1.2.1",
            "androidx.datastore:datastore-core-okio-jvm:1.2.1",
            "androidx.datastore:datastore-core-okio:1.2.1",
            "androidx.datastore:datastore-core:1.2.1",
            "androidx.datastore:datastore-preferences-android:1.2.1",
            "androidx.datastore:datastore-preferences-core-android:1.2.1",
            "androidx.datastore:datastore-preferences-core:1.2.1",
            "androidx.datastore:datastore-preferences-external-protobuf:1.2.1",
            "androidx.datastore:datastore-preferences-proto:1.2.1",
            "androidx.datastore:datastore-preferences:1.2.1",
            "androidx.datastore:datastore:1.2.1",
            "com.google.code.findbugs:jsr305:3.0.2",
            "com.google.code.gson:gson:2.13.2",
            "com.google.crypto.tink:tink-android:1.23.0",
            "com.google.errorprone:error_prone_annotations:2.41.0",
            "com.squareup.okio:okio-jvm:3.9.1",
            "com.squareup.okio:okio:3.9.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-bom:1.7.3",
            "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3",
            "org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3",
            "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3",
            "org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3",
            "project::sdk-core",
            "project::sdk-playback",
        ),
    )
    this["sdk-playback"] = ProductionGraph(
        direct = setOf("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"),
        resolved = coroutineResolved,
    )
    this["sdk-media3"] = ProductionGraph(
        direct = setOf(
            "project::sdk-playback",
            "androidx.media3:media3-exoplayer:1.11.0",
            "androidx.media3:media3-extractor:1.11.0",
            "file:media3-decoder-ffmpeg-1.11.0.jar",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
        ),
        resolved = setOf(
            "androidx.annotation:annotation-experimental:1.3.1",
            "androidx.annotation:annotation-jvm:1.6.0",
            "androidx.annotation:annotation:1.6.0",
            "androidx.exifinterface:exifinterface:1.3.6",
            "androidx.media3:media3-common:1.11.0",
            "androidx.media3:media3-container:1.11.0",
            "androidx.media3:media3-database:1.11.0",
            "androidx.media3:media3-datasource:1.11.0",
            "androidx.media3:media3-decoder:1.11.0",
            "androidx.media3:media3-exoplayer:1.11.0",
            "androidx.media3:media3-extractor:1.11.0",
            "com.google.guava:failureaccess:1.0.2",
            "com.google.guava:guava:33.3.1-android",
            "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava",
            "org.jetbrains.kotlin:kotlin-stdlib:2.4.10",
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
            "org.jetbrains:annotations:23.0.0",
            "project::sdk-playback",
        ),
    )
    this["sdk-testing"] = ProductionGraph(
        direct = setOf("project::sdk-core", "project::sdk-playback"),
        resolved = (if (useHtspComposite) {
            (coreResolved - "at.bernhardberger.tvheadend:htsp:0.6.0") + "project::tvheadend-htsp"
        } else {
            coreResolved
        }) + setOf("project::sdk-core", "project::sdk-playback"),
    )
}
val scopedDirectDependencies = sdkModules.associateWith { emptySet<String>() }.toMutableMap().apply {
    this["sdk-core"] = setOf(
        "api=project::sdk-playback",
        "api=org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
        "implementation=at.bernhardberger.tvheadend:htsp:0.6.0",
    )
    this["sdk-android"] = setOf(
        "api=project::sdk-core",
        "api=org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
        "implementation=androidx.datastore:datastore-preferences:1.2.1",
        "implementation=com.google.crypto.tink:tink-android:1.23.0",
    )
    this["sdk-playback"] = setOf(
        "api=org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
    )
    this["sdk-media3"] = setOf(
        "api=project::sdk-playback",
        "api=androidx.media3:media3-exoplayer:1.11.0",
        "implementation=androidx.media3:media3-extractor:1.11.0",
        "implementation=file:media3-decoder-ffmpeg-1.11.0.jar",
        "implementation=org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
    )
    this["sdk-testing"] = setOf(
        "api=project::sdk-core",
        "api=project::sdk-playback",
    )
}

fun Project.registerProductionDependencyVerification(
    directConfigurationNames: Set<String>,
    resolvedConfigurationNames: Set<String>,
) {
    val expected = productionGraphs.getValue(name)
    val expectedScopedDirect = scopedDirectDependencies.getValue(name)
    val directDependencies = providers.provider {
        directConfigurationNames
            .flatMap { configurationName -> configurations.getByName(configurationName).dependencies }
            .filterNot { dependency ->
                dependency.group == "org.jetbrains.kotlin" && dependency.name == "kotlin-stdlib"
            }
            .map { dependency ->
                when (dependency) {
                    is ProjectDependency -> "project:${dependency.path}"
                    is FileCollectionDependency -> "file:${dependency.files.single().name}"
                    else -> "${dependency.group}:${dependency.name}:${dependency.version}"
                }
            }
            .toSortedSet()
            .toList()
    }
    val scopedDirect = providers.provider {
        directConfigurationNames.flatMap { configurationName ->
            configurations.getByName(configurationName).dependencies
                .filterNot { dependency ->
                    dependency.group == "org.jetbrains.kotlin" && dependency.name == "kotlin-stdlib"
                }
                .map { dependency ->
                    val coordinate = when (dependency) {
                        is ProjectDependency -> "project:${dependency.path}"
                        is FileCollectionDependency -> "file:${dependency.files.single().name}"
                        else -> "${dependency.group}:${dependency.name}:${dependency.version}"
                    }
                    "$configurationName=$coordinate"
                }
        }.toSortedSet().toList()
    }
    val resolvedDependencies = providers.provider {
        resolvedConfigurationNames
            .flatMap { configurationName ->
                configurations.getByName(configurationName).incoming.resolutionResult.allComponents
            }
            .mapNotNull { component ->
                when (val identifier = component.id) {
                    is ModuleComponentIdentifier ->
                        "${identifier.group}:${identifier.module}:${identifier.version}"
                    is ProjectComponentIdentifier ->
                        identifier.buildTreePath
                            .takeUnless { buildTreePath -> buildTreePath == path }
                            ?.let { buildTreePath -> "project:$buildTreePath" }
                    else -> null
                }
            }
            .toSortedSet()
            .toList()
    }
    val verify = tasks.register("verifyProductionDependencyGraph") {
        group = "verification"
        description = "Checks $name's fail-closed production dependency allowlist."
        inputs.property("directDependencies", directDependencies)
        inputs.property("scopedDirectDependencies", scopedDirect)
        inputs.property("resolvedDependencies", resolvedDependencies)
        inputs.property("expectedDirectDependencies", expected.direct.toSortedSet().toList())
        inputs.property(
            "expectedScopedDirectDependencies",
            expectedScopedDirect.toSortedSet().toList(),
        )
        inputs.property("expectedResolvedDependencies", expected.resolved.toSortedSet().toList())
        doLast {
            val direct = (inputs.properties.getValue("directDependencies") as List<*>).filterIsInstance<String>().toSet()
            val resolved = (inputs.properties.getValue("resolvedDependencies") as List<*>).filterIsInstance<String>().toSet()
            val scoped = (inputs.properties.getValue("scopedDirectDependencies") as List<*>)
                .filterIsInstance<String>()
                .toSet()
            val expectedDirect = (inputs.properties.getValue("expectedDirectDependencies") as List<*>)
                .filterIsInstance<String>()
                .toSet()
            val expectedResolved = (inputs.properties.getValue("expectedResolvedDependencies") as List<*>)
                .filterIsInstance<String>()
                .toSet()
            val expectedScoped = (inputs.properties.getValue("expectedScopedDirectDependencies") as List<*>)
                .filterIsInstance<String>()
                .toSet()
            check(direct == expectedDirect) {
                "Unexpected direct production dependencies: $direct"
            }
            check(resolved == expectedResolved) {
                "Unexpected resolved production dependencies: $resolved"
            }
            check(scoped == expectedScoped) {
                "Unexpected scoped production dependencies: $scoped"
            }
        }
    }
    tasks.matching { task -> task.name == "check" }.configureEach {
        dependsOn(verify)
    }
}

subprojects {
    val moduleName = project.name

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
        from(rootProject.file("NOTICE.md")) {
            into("META-INF")
        }
    }

    val productionKotlinCompiles = tasks.withType<KotlinJvmCompile>().matching { task ->
        !task.name.contains("Test")
    }
    val verifyClassMajor61 = tasks.register("verifyClassMajor61") {
        group = "verification"
        description = "Checks every production Kotlin class uses Java 17 class-file major version 61."
        dependsOn(productionKotlinCompiles)
        inputs.files(productionKotlinCompiles)
        doLast {
            val classes = inputs.files.asFileTree.matching {
                include("**/*.class")
            }.files.sortedBy { file -> file.invariantSeparatorsPath }
            check(classes.isNotEmpty()) { "No production class files were found in $path" }
            classes.forEach { file ->
                val header = file.inputStream().use { input -> input.readNBytes(8) }
                check(header.size == 8 && header[0] == 0xCA.toByte() && header[1] == 0xFE.toByte()) {
                    "Malformed production class in $moduleName"
                }
                val major = (header[6].toInt() and 0xff) * 256 + (header[7].toInt() and 0xff)
                check(major == 61) { "$moduleName contains class-file major $major, expected 61" }
            }
        }
    }
    tasks.matching { task -> task.name == "check" }.configureEach {
        dependsOn(verifyClassMajor61)
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        if (moduleName == "sdk-core") {
            val kotlinPluginVersion = plugins.getPlugin("org.jetbrains.kotlin.jvm")
                .let { plugin -> (plugin as KotlinBasePlugin).pluginVersion }
            verifyBuildMatrix.configure {
                inputs.property("loadedKotlinPluginVersion", kotlinPluginVersion)
            }
        }
        registerProductionDependencyVerification(
            directConfigurationNames = setOf(
                "api",
                "compileOnly",
                "compileOnlyApi",
                "implementation",
                "runtimeOnly",
            ),
            resolvedConfigurationNames = setOf("compileClasspath", "runtimeClasspath"),
        )
    }
    pluginManager.withPlugin("com.android.library") {
        val android = extensions.getByType<LibraryExtension>()
        verifyBuildMatrix.configure {
            inputs.property("$moduleName.kotlinAndroidApplied", pluginManager.hasPlugin("org.jetbrains.kotlin.android"))
            inputs.property("$moduleName.compileSdk", providers.provider { android.compileSdk })
            inputs.property("$moduleName.buildTools", providers.provider { android.buildToolsVersion })
            inputs.property("$moduleName.minSdk", providers.provider { android.defaultConfig.minSdk })
            inputs.property("$moduleName.targetSdk", providers.provider { android.testOptions.targetSdk })
            inputs.property("$moduleName.testRunner", providers.provider { android.defaultConfig.testInstrumentationRunner })
        }
        registerProductionDependencyVerification(
            directConfigurationNames = setOf(
                "api",
                "compileOnly",
                "compileOnlyApi",
                "debugApi",
                "debugCompileOnly",
                "debugCompileOnlyApi",
                "debugImplementation",
                "debugRuntimeOnly",
                "implementation",
                "releaseApi",
                "releaseCompileOnly",
                "releaseCompileOnlyApi",
                "releaseImplementation",
                "releaseRuntimeOnly",
                "runtimeOnly",
            ),
            resolvedConfigurationNames = setOf(
                "debugCompileClasspath",
                "debugRuntimeClasspath",
                "releaseCompileClasspath",
                "releaseRuntimeClasspath",
            ),
        )
        tasks.matching { task -> task.name == "check" }.configureEach {
            dependsOn("assembleDebugAndroidTest")
        }
    }

    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "checkoutLocal"
                    url = rootProject.layout.buildDirectory.dir("local-maven").get().asFile.toURI()
                }
            }
            publications.withType<MavenPublication>().configureEach {
                artifactId = project.name
                pom {
                    name.set(project.name)
                    description.set(publicationDescriptions.getValue(project.name))
                    url.set("https://github.com/bernhardberger/tvheadend-sdk")
                    licenses {
                        license {
                            name.set("GNU General Public License v3.0")
                            url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("bernhardberger")
                            name.set("Bernhard Berger")
                            url.set("https://github.com/bernhardberger")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/bernhardberger/tvheadend-sdk.git")
                        developerConnection.set("scm:git:ssh://git@github.com/bernhardberger/tvheadend-sdk.git")
                        url.set("https://github.com/bernhardberger/tvheadend-sdk")
                        tag.set("HEAD")
                    }
                    issueManagement {
                        system.set("GitHub Issues")
                        url.set("https://github.com/bernhardberger/tvheadend-sdk/issues")
                    }
                    properties.put("tvheadend.sdk.notice.path", "NOTICE.md")
                }
            }
        }
        tasks.withType<PublishToMavenLocal>().configureEach {
            enabled = false
        }

    }
}

tasks.named("clean") {
    dependsOn(subprojects.map { module -> "${module.path}:clean" })
}
tasks.named("assemble") {
    dependsOn(subprojects.map { module -> "${module.path}:assemble" })
}
tasks.named("check") {
    dependsOn(verifyBuildMatrix)
    dependsOn(subprojects.map { module -> "${module.path}:check" })
}

tasks.register("stageLocalPublication") {
    group = "publishing"
    description = "Stages all five SDK publications under build/local-maven."
    dependsOn(subprojects.map { module -> "${module.path}:publishAllPublicationsToCheckoutLocalRepository" })
}
