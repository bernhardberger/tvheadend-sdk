import com.android.build.api.dsl.LibraryExtension
import dev.detekt.gradle.Detekt
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
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
        resources.directories.add(file("src/androidTest/assets").path)
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
    implementation(files("libs/media3-decoder-ffmpeg-1.11.0.jar"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
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

val ffmpegArtifactChecksums = mapOf(
    "libs/media3-decoder-ffmpeg-1.11.0.jar" to
        "7288000961aee5aa9c9e72f895701d71b11b002d554a231d2031f7af52865ed6",
    "src/main/jniLibs/arm64-v8a/libffmpegJNI.so" to
        "d46c1e296e5f897518e0d3f01e45dbf04158dc332b16fd616ffe4287ab4ba6d9",
    "src/main/jniLibs/armeabi-v7a/libffmpegJNI.so" to
        "34db1ebf539808a81e0fb77d62b84d092588aefae816193175a75ebf4d89ae89",
    "src/main/jniLibs/x86/libffmpegJNI.so" to
        "cc20fbab7596be4cc24874f76c9052560972537820878365d95fb7f0445dbbc8",
    "src/main/jniLibs/x86_64/libffmpegJNI.so" to
        "1f25550a22a1de880de8260b6d5f1c881a062a21244e199e93c286a77062a845",
    "../third_party/ffmpeg/ffmpeg-6.0-3f92512f-sources.tar.xz" to
        "9eeca8490f794574185986c0df7800d65ccca2980f57dc26b630a398581d7929",
)
val ffmpegArtifactFiles = ffmpegArtifactChecksums.mapKeys { (path, _) -> file(path).absolutePath }

val verifyFfmpegArtifacts = tasks.register("verifyFfmpegArtifacts") {
    group = "verification"
    description = "Verifies the independently built FFmpeg binaries and corresponding source."
    inputs.files(ffmpegArtifactFiles.keys)
    inputs.property("checksums", ffmpegArtifactFiles)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val expectedChecksums = inputs.properties.getValue("checksums") as Map<String, String>
        inputs.files.files.forEach { artifact ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(artifact.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            check(digest == expectedChecksums.getValue(artifact.absolutePath)) {
                "FFmpeg artifact checksum mismatch: ${artifact.name}"
            }
        }
    }
}

tasks.named("check").configure {
    dependsOn(verifyFfmpegArtifacts)
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
                artifact(rootProject.file("third_party/ffmpeg/ffmpeg-6.0-3f92512f-sources.tar.xz")) {
                    classifier = "ffmpeg-sources"
                    extension = "tar.xz"
                }
            }
        }
    }
}
