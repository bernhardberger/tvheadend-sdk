import dev.detekt.gradle.Detekt
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    `java-test-fixtures`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
}

dokka {
    dokkaPublications.html {
        moduleName.set("Tvheadend Kotlin SDK: Core")
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

val benchmarkSourceSet = sourceSets.create("benchmark")
kotlin.target.compilations.getByName("benchmark").associateWith(kotlin.target.compilations.getByName("main"))

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

benchmark {
    targets.register("benchmark")
    configurations {
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "us"
            advanced("jvmForks", 2)
        }
        register("smoke") {
            warmups = 1
            iterations = 1
            iterationTime = 100
            iterationTimeUnit = "ms"
            outputTimeUnit = "us"
            advanced("jvmForks", 1)
        }
    }
}

// Match the HTSP harness: kotlinx-benchmark 0.5.0 generates JMH but has no profiler
// option. Configure its JavaExec tasks after registration, before cache snapshots.
afterEvaluate {
    benchmark.configurations.forEach { configuration ->
        val configurationName = configuration.name
        val taskName = "benchmark${configuration.capitalizedName()}Benchmark"
        val unsupported = buildList {
            if (configuration.includes.isNotEmpty()) add("includes")
            if (configuration.excludes.isNotEmpty()) add("excludes")
            if (configuration.params.isNotEmpty()) add("params")
            if (configuration.mode != null) add("mode")
            if (configuration.reportFormat != null) add("reportFormat")
            if (configuration.customEngine != null) add("customEngine")
            addAll(configuration.advanced.keys.filter { it != "jvmForks" }.map { "advanced.$it" })
        }
        check(unsupported.isEmpty()) {
            "JMH GC-profiler CLI override cannot translate '$configurationName' settings: $unsupported"
        }
        check(listOf(configuration.warmups, configuration.iterations, configuration.iterationTime,
            configuration.iterationTimeUnit, configuration.outputTimeUnit, configuration.advanced["jvmForks"])
            .all { it != null }) {
            "JMH GC-profiler CLI override requires explicit warmups, iterations, iterationTime, " +
                "iterationTimeUnit, outputTimeUnit and advanced.jvmForks for '$configurationName'"
        }
        val iterationDuration = "${configuration.iterationTime}${configuration.iterationTimeUnit}"
        tasks.named<JavaExec>(taskName) {
            val report = layout.buildDirectory.file("reports/benchmarks/$configurationName/benchmark.json")
            outputs.file(report)
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("Benchmark measurements must run on the current machine") { true }
            mainClass.set("org.openjdk.jmh.Main")
            setArgs(listOf(
                "-wi", configuration.warmups.toString(), "-i", configuration.iterations.toString(),
                "-w", iterationDuration, "-r", iterationDuration,
                "-f", configuration.advanced.getValue("jvmForks").toString(),
                "-tu", configuration.outputTimeUnit, "-prof", "gc", "-foe", "true",
                "-rf", "json", "-rff", report.get().asFile.absolutePath,
                "-jvmArgs", "-Xms512m -Xmx1g",
            ))
            doFirst { report.get().asFile.parentFile.mkdirs() }
        }
    }
}

// Compile fixtures and the generated harness on checks; timing runs remain explicit.
tasks.named("check") {
    dependsOn("benchmarkBenchmarkCompile")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(project(":sdk-playback"))
    implementation(libs.htsp)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.konsist)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    testFixturesImplementation(libs.kotlinx.coroutines.test)
    add(benchmarkSourceSet.implementationConfigurationName, libs.kotlinx.benchmark.runtime)
}

tasks.named<Test>("test") {
    // Architecture tests inspect sibling source directly, outside this module's test classpath.
    inputs.files(rootProject.fileTree(".") {
        include("sdk-*/src/main/**/*.kt", "sdk-*/src/main/**/*.java")
        include("sdk-*/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle/libs.versions.toml")
    }).withPropertyName("architectureSources").withPathSensitivity(PathSensitivity.RELATIVE)
    useJUnitPlatform {
        // Credentials and JUnit condition overrides must not opt ordinary checks into server access.
        excludeTags("live-soak", "live-dvr")
    }
}

tasks.register<Test>("liveServerTest") {
    description = "Runs live EPG/DVR verification; requires separate server read/mutation authorization."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // Server state and credentials are not reproducible Gradle task inputs.
    outputs.upToDateWhen { false }
    useJUnitPlatform {
        includeTags("live-soak", "live-dvr")
    }
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    source.setFrom("src/main/kotlin")
}

tasks.withType<Detekt>().configureEach {
    setSource(files("src/main/kotlin"))
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
tasks.named<Jar>("javadocJar") {
    from(tasks.dokkaGeneratePublicationHtml.flatMap { task -> task.outputDirectory })
    from(rootProject.file("README.md")) {
        into("docs")
        rename { "ROOT_README.md" }
    }
    from(rootProject.file("docs")) {
        include("**/*.md")
        into("docs")
    }
}

(components["java"] as AdhocComponentWithVariants).apply {
    withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
    withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
        }
    }
}
