package at.bernhardberger.tvheadend.sdk.core.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

internal class ModuleBoundaryTest {
    private val modulePackages = mapOf(
        "sdk-android" to "at.bernhardberger.tvheadend.sdk.android",
        "sdk-core" to "at.bernhardberger.tvheadend.sdk.core",
        "sdk-media3" to "at.bernhardberger.tvheadend.sdk.media3",
        "sdk-playback" to "at.bernhardberger.tvheadend.sdk.playback",
        "sdk-testing" to "at.bernhardberger.tvheadend.sdk.testing",
    )

    @Test
    fun `production sources stay inside their module package`() {
        modulePackages.forEach { (module, packagePrefix) ->
            assertFalse(File("$module/src/main/java").exists(), "$module must use Kotlin production sources")
            val files = productionScope(module).files
            assertFalse(files.isEmpty(), "$module must have production source")
            files.assertTrue { file ->
                val packageName = file.packagee?.name.orEmpty()
                packageName == packagePrefix || packageName.startsWith("$packagePrefix.")
            }
        }
    }

    @Test
    fun `pure JVM modules import no Android or Media3 types`() {
        val forbiddenPrefixes = listOf("android.", "androidx.", "com.android.")
        listOf("sdk-core", "sdk-playback", "sdk-testing").forEach { module ->
            productionScope(module).files.assertTrue { file ->
                forbiddenPrefixes.none(file.text::contains) &&
                    file.imports.none { declaration ->
                        forbiddenPrefixes.any(declaration.name::startsWith)
                    }
            }
        }
    }

    @Test
    fun `only the core gateway implementation may import HTSP`() {
        modulePackages.keys.forEach { module ->
            productionScope(module).files.assertTrue { file ->
                val htspPrefix = "at.bernhardberger.tvheadend.htsp."
                val referencesHtsp = file.text.contains(htspPrefix) || file.imports.any { declaration ->
                    declaration.name.startsWith(htspPrefix)
                }
                val packageName = file.packagee?.name.orEmpty()
                val gatewayPackage = "at.bernhardberger.tvheadend.sdk.core.gateway.htsp"
                !referencesHtsp || packageName == gatewayPackage || packageName.startsWith("$gatewayPackage.")
            }
        }
    }

    private fun productionScope(module: String) =
        Konsist.scopeFromDirectory("$module/src/main/kotlin")
}
