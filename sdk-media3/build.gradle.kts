import com.android.build.api.dsl.LibraryExtension
import dev.detekt.gradle.Detekt
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
}

dokka {
    dokkaPublications.html {
        moduleName.set("TVHeadend Kotlin SDK: Media3")
    }
}

extensions.configure<LibraryExtension> {
    namespace = "at.bernhardberger.tvheadend.sdk.media3"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        targetSdk = libs.versions.targetSdk.get().toInt()
        unitTests {
            isReturnDefaultValues = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.named("main") {
        resources.directories.add(rootProject.file("legal").path)
    }
    sourceSets.named("test") {
        kotlin.directories.add(file("src/sharedTest/kotlin").path)
        resources.directories.add(file("src/androidTest/assets").path)
    }
    sourceSets.named("androidTest") {
        kotlin.directories.add(file("src/sharedTest/kotlin").path)
    }
    packaging {
        resources.excludes.remove("/META-INF/LICENSE")
        resources.excludes.remove("/META-INF/NOTICE")
        resources.pickFirsts.add("/META-INF/LICENSE")
        resources.pickFirsts.add("/META-INF/NOTICE")
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    api(project(":sdk-core"))
    api(project(":sdk-playback"))
    api(libs.media3.exoplayer)
    implementation(libs.media3.extractor)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":sdk-testing"))
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(project(":sdk-core"))
    androidTestImplementation(project(":sdk-testing"))
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    source.setFrom("src/main/kotlin")
}

tasks.withType<Detekt>().configureEach {
    setSource(files("src/main/kotlin"))
}

val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
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

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifact(javadocJar)
            }
        }
    }
}
