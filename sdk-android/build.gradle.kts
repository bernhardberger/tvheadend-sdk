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

extensions.configure<LibraryExtension> {
    namespace = "at.bernhardberger.tvheadend.sdk.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        targetSdk = libs.versions.targetSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.named("main") {
        resources.directories.add(rootProject.file("legal").path)
    }
    packaging {
        resources.excludes.remove("/META-INF/LICENSE")
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
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
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
