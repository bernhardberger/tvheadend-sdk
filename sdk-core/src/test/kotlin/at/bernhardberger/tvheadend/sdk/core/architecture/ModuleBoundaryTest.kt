package at.bernhardberger.tvheadend.sdk.core.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoBaseDeclaration
import com.lemonappdev.konsist.api.declaration.KoConstructorDeclaration
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import com.lemonappdev.konsist.api.declaration.KoParameterDeclaration
import com.lemonappdev.konsist.api.declaration.KoPropertyDeclaration
import com.lemonappdev.konsist.api.declaration.KoTypeArgumentDeclaration
import com.lemonappdev.konsist.api.declaration.combined.KoClassAndInterfaceAndObjectDeclaration
import com.lemonappdev.konsist.api.declaration.type.KoTypeDeclaration
import com.lemonappdev.konsist.api.provider.KoContainingDeclarationProvider
import com.lemonappdev.konsist.api.provider.KoDeclarationCastProvider
import com.lemonappdev.konsist.api.provider.KoSourceDeclarationProvider
import com.lemonappdev.konsist.api.provider.modifier.KoVisibilityModifierProvider
import com.lemonappdev.konsist.api.verify.assertTrue
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ModuleBoundaryTest {
    private val repositoryRoot = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { file ->
        file.parentFile
    }.first { file -> File(file, "settings.gradle.kts").isFile }

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
            val hasJavaProductionSource = File(repositoryRoot, "$module/src/main")
                .walkTopDown()
                .any { file -> file.isFile && file.extension == "java" }
            assertFalse(
                hasJavaProductionSource,
                "$module must use Kotlin production sources",
            )
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
    fun `only the Android service module imports Coil`() {
        modulePackages.keys.filterNot { module -> module == "sdk-android" }.forEach { module ->
            productionScope(module).files.assertTrue { file ->
                !file.text.contains("coil3.") &&
                    file.imports.none { declaration -> declaration.name.startsWith("coil3.") }
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

    @Test
    fun `playback and testing never depend on core gateway internals`() {
        listOf("sdk-playback", "sdk-testing").forEach { module ->
            productionScope(module).files.assertTrue { file ->
                val forbiddenPrefix = "at.bernhardberger.tvheadend.sdk.core.gateway"
                !file.text.contains(forbiddenPrefix) &&
                    file.imports.none { declaration -> declaration.name.startsWith(forbiddenPrefix) }
            }
        }
    }

    @Test
    fun `HTSP implementation contains no public top level SDK declarations`() {
        val publicDeclaration = Regex(
            pattern = "^public\\s+(?:(?:data|sealed)\\s+)*(?:class|interface|object|fun|val|var|typealias)\\b",
            option = RegexOption.MULTILINE,
        )
        productionScope("sdk-core").files
            .filter { file ->
                val packageName = file.packagee?.name.orEmpty()
                packageName == "at.bernhardberger.tvheadend.sdk.core.gateway.htsp" ||
                    packageName.startsWith("at.bernhardberger.tvheadend.sdk.core.gateway.htsp.")
            }
            .assertTrue { file -> !publicDeclaration.containsMatchIn(file.text) }
    }

    @Test
    fun `public SDK type set remains deliberate and reachable from the session API`() {
        val publicType = Regex(
            "public\\s+(?:(?:data|sealed|value)\\s+)*(?:class|interface|enum\\s+class|object)\\s+(\\w+)",
        )
        val coreApi = productionScope("sdk-core").files
            .filter { file -> file.packagee?.name == "at.bernhardberger.tvheadend.sdk.core" }
            .joinToString("\n") { file -> file.text }
        val actual = publicType.findAll(coreApi).map { match -> match.groupValues[1] }.toSet()
        val expected = setOf(
            "ChannelId",
            "ArtworkId",
            "ArtworkFailure",
            "ArtworkContent",
            "ArtworkLoadResult",
            "ArtworkLoader",
            "ChannelTagId",
            "EventId",
            "EpgEpisodeId",
            "EpgSeriesLinkId",
            "DvrEntryId",
            "AutorecRuleId",
            "TimerecRuleId",
            "DvrConfigId",
            "DvrEntryState",
            "DvrSubscriptionError",
            "DvrRecordingFile",
            "DvrEntry",
            "AutorecRule",
            "TimerecRule",
            "DvrSnapshot",
            "DvrRepositoryState",
            "DvrConfiguration",
            "DvrDiskSpace",
            "DvrConfigurationsState",
            "DvrDiskSpaceState",
            "DvrRepository",
            "DvrSchedule",
            "Programme",
            "ExplicitTime",
            "DvrScheduleRequest",
            "DvrEntryUpdate",
            "RecordingRuleChannel",
            "SpecificChannel",
            "AllChannels",
            "AutorecRuleCreate",
            "AutorecRuleUpdate",
            "TimerecRuleCreate",
            "TimerecRuleUpdate",
            "DvrMutationResult",
            "Confirmed",
            "AcceptedButUnconfirmed",
            "DvrPlaybackProgress",
            "DvrPlaybackExit",
            "DvrProgressResult",
            "Accepted",
            "DvrProgressPolicy",
            "DvrResumeOffer",
            "Resume",
            "StartOver",
            "DvrProgressTracker",
            "DvrCutpointAction",
            "DvrCutpoint",
            "DvrCutpointsResult",
            "Available",
            "NotReady",
            "ServerRejected",
            "AccessDenied",
            "ConnectionLimit",
            "Timeout",
            "NotSupported",
            "ChannelService",
            "Channel",
            "ChannelTag",
            "ChannelCatalog",
            "ChannelRepositoryState",
            "Empty",
            "Unknown",
            "Denied",
            "Synchronizing",
            "Current",
            "Stale",
            "ChannelRepository",
            "EpgRating",
            "EpgEpisode",
            "EpgEvent",
            "EpgCoverage",
            "EpgCoverageRequestResult",
            "EpgSnapshot",
            "EpgRepositoryState",
            "EpgRepository",
            "TvheadendSession",
            "ServerProfile",
            "ServerAuthentication",
            "Anonymous",
            "Password",
            "SessionCommandResult",
            "SessionState",
            "Disconnected",
            "Connecting",
            "Synchronizing",
            "Ready",
            "Unavailable",
            "SessionFailure",
            "AuthenticationRejected",
            "PermissionDenied",
            "ServerUnreachable",
            "NetworkUnavailable",
            "IncompatibleServer",
            "NoChannels",
            "TransportUnavailable",
            "SynchronizationFailed",
            "UnexpectedFailure",
            "SessionOperationFailure",
            "ServerCapabilities",
            "CapabilityAccess",
            "RecordingProgressCapability",
            "StreamProfileId",
            "StreamProfile",
            "StreamProfilesResult",
        )

        assertEquals(expected, actual)
    }

    @Test
    fun `public suspending SDK calls use typed outcomes or lifecycle Unit`() {
        val publicApi = listOf(
            "src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/TvheadendSession.kt",
            "src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/DvrRepository.kt",
            "../sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendPlaybackCoordinator.kt",
        ).joinToString(" ") { path -> File(path).readText() }.replace(Regex("\\s+"), " ")
        val expectedSignatures = setOf(
            "public suspend fun connect(profile: ServerProfile): SessionCommandResult",
            "public suspend fun retry(): SessionCommandResult",
            "public suspend fun disconnect()",
            "public suspend fun shutdown()",
            "public suspend fun getStreamProfiles(): StreamProfilesResult",
            "public suspend fun scheduleEntry(request: DvrScheduleRequest): DvrMutationResult<DvrEntryId>",
            "public suspend fun updateEntry( id: DvrEntryId, update: DvrEntryUpdate, ): DvrMutationResult<Unit>",
            "public suspend fun stopEntry(id: DvrEntryId): DvrMutationResult<Unit>",
            "public suspend fun cancelEntry(id: DvrEntryId): DvrMutationResult<Unit>",
            "public suspend fun deleteEntry(id: DvrEntryId): DvrMutationResult<Unit>",
            "public suspend fun createAutorecRule( request: AutorecRuleCreate, ): DvrMutationResult<AutorecRuleId>",
            "public suspend fun updateAutorecRule( id: AutorecRuleId, update: AutorecRuleUpdate, ): DvrMutationResult<Unit>",
            "public suspend fun deleteAutorecRule(id: AutorecRuleId): DvrMutationResult<Unit>",
            "public suspend fun createTimerecRule( request: TimerecRuleCreate, ): DvrMutationResult<TimerecRuleId>",
            "public suspend fun updateTimerecRule( id: TimerecRuleId, update: TimerecRuleUpdate, ): DvrMutationResult<Unit>",
            "public suspend fun deleteTimerecRule(id: TimerecRuleId): DvrMutationResult<Unit>",
            "public suspend fun reportProgress( id: DvrEntryId, progress: DvrPlaybackProgress, ): DvrProgressResult",
            "public suspend fun reportProgress( lease: GrowingRecordingFileLease, progress: DvrPlaybackProgress, ): DvrProgressResult",
            "public suspend fun cutpoints(id: DvrEntryId): DvrCutpointsResult",
            "public suspend fun run()",
            "public suspend fun setLiveTarget(channelId: ChannelId): PlaybackTargetResult",
            "public suspend fun setLiveTarget( channelId: ChannelId, options: LivePlaybackOptions, ): PlaybackTargetResult",
            "public suspend fun setRecordingTarget( recordingId: DvrEntryId, start: RecordingPlaybackStart = RecordingPlaybackStart.RESUME, ): PlaybackTargetResult",
            "public suspend fun seekTimeshift(offset: Duration): TimeshiftCommandResult",
            "public suspend fun returnToLive(): TimeshiftCommandResult",
            "public suspend fun pauseTimeshift(): TimeshiftCommandResult",
            "public suspend fun resumeTimeshift(): TimeshiftCommandResult",
            "public suspend fun stop(): PlaybackStopResult",
            "public suspend fun shutdown(drainTimeout: Duration): PlaybackShutdownResult",
        )

        assertEquals(expectedSignatures.size, Regex("public suspend fun ").findAll(publicApi).count())
        expectedSignatures.forEach { signature ->
            org.junit.jupiter.api.Assertions.assertTrue(
                publicApi.contains(signature),
                "Missing typed public suspending signature",
            )
        }
    }

    @Test
    fun `playback and testing infrastructure APIs remain deliberate and opt in`() {
        val expectedPlayback = setOf(
            "SubscriptionInfrastructureApi",
            "SubscriptionId",
            "SubscriptionChannelId",
            "StreamIndex",
            "SubscriptionBinary",
            "SubscriptionEvent",
            "SubscriptionStream",
            "SubscriptionStreamType",
            "SubscriptionIssue",
            "SubscriptionCondition",
            "MuxFrameType",
            "SkipOutcome",
            "SubscriptionTermination",
            "SubscriptionOperationResult",
            "SubscriptionConfirmation",
            "SubscriptionOptions",
            "SubscriptionSeekTarget",
            "SubscriptionConnection",
            "SubscriptionEventConsumer",
            "SubscriptionTracks",
            "SubscriptionState",
            "SubscriptionTerminalReason",
            "SubscriptionSeekInvalidation",
            "SubscriptionSeekResult",
            "SubscriptionOperationFailure",
            "SubscriptionDiagnostics",
            "SubscriptionOpenResult",
            "SubscriptionCloseResult",
            "ActiveSubscription",
            "SubscriptionOpener",
            "SubscriptionManager",
            "createSubscriptionManager",
            "RecordingId",
            "RecordingFileFailure",
            "RecordingFileResult",
            "RecordingFile",
            "RecordingFileOpener",
            "GrowingRecordingFileLease",
            "GrowingRecordingFileReader",
            "RecordingFileReader",
            "createRecordingFileReader",
            "MAX_RECORDING_READ_BYTES",
            "DEFAULT_RECORDING_READ_AHEAD_BYTES",
            "RECORDING_END_OF_INPUT",
        )
        val expectedTesting = setOf(
            "FakeChannelRepository",
            "FakeEpgRepository",
            "FakeDvrRepository",
            "FakeDvrProgressCall",
            "ScriptedSubscriptionCall",
            "ScriptedSubscriptionConnection",
            "ScriptedSubscriptionRegistration",
            "SubscriptionBinaryFixture",
        )
        val expectedMedia3 = setOf(
            "LiveTimeshiftState",
            "TimeshiftCommandResult",
            "PlaybackShutdownResult",
            "PlaybackRecoveryPolicy",
            "PlaybackRecoveryReason",
            "PlaybackStopResult",
            "PlaybackTargetResult",
            "RecordingPlaybackStart",
            "LivePlaybackOptions",
            "TvheadendPlaybackRecovery",
            "TvheadendPlaybackCoordinator",
            "TvheadendRecordingException",
            "TvheadendRecordingResume",
            "createTvheadendLiveMediaSource",
            "createTvheadendPlaybackRecovery",
            "createTvheadendPlaybackCoordinator",
            "createTvheadendRenderersFactory",
            "createTvheadendRecordingDataSourceFactory",
            "createTvheadendRecordingMediaSource",
            "createTvheadendRecordingResume",
            "tvheadendRecordingMediaItem",
        )
        val expectedAndroid = setOf(
            "CredentialOperationResult",
            "CredentialReadResult",
            "ServerProfileAuthenticationMode",
            "ServerProfileOperationResult",
            "ServerProfileReadResult",
            "TvheadendDiscovery",
            "DiscoveredTvheadendServer",
            "TvheadendDiscoveryState",
            "TvheadendDiscoveryFailure",
            "TvheadendConnectivity",
            "TvheadendConnectivityStatus",
            "TvheadendCredentialStore",
            "TvheadendServerProfileStore",
            "TvheadendArtwork",
            "createTvheadendArtworkFetcherFactory",
        )

        assertPublicInfrastructure("sdk-android", expectedAndroid, unannotatedCount = expectedAndroid.size)
        val credentialStoreSource = File(
            repositoryRoot,
            "sdk-android/src/main/kotlin/at/bernhardberger/tvheadend/sdk/android/" +
                "TvheadendCredentialStore.kt",
        ).readText()
        assertTrue(
            Regex(
                """@Deprecated\(\s*message = \"Use TvheadendServerProfileStore\",\s*""" +
                    """level = DeprecationLevel\.WARNING,\s*\)\s*""" +
                    """public class TvheadendCredentialStore""",
            ).containsMatchIn(credentialStoreSource),
            "The compatibility credential store must be warning-deprecated without ReplaceWith",
        )
        // The codec classification is intentionally stable for sdk-media3 application callbacks.
        assertPublicInfrastructure("sdk-playback", expectedPlayback, unannotatedCount = 3)
        assertPublicInfrastructure("sdk-testing", expectedTesting, unannotatedCount = 4)
        assertPublicInfrastructure("sdk-media3", expectedMedia3, unannotatedCount = 13)

        val sessionApi = java.io.File(
            "src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/TvheadendSession.kt",
        ).readText()
        org.junit.jupiter.api.Assertions.assertTrue(
            Regex(
                "@SubscriptionInfrastructureApi\\s+public val subscriptions: SubscriptionOpener",
            ).containsMatchIn(sessionApi),
            "Missing opted-in subscription opener on the public session",
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            Regex(
                "@SubscriptionInfrastructureApi\\s+public val recordings: RecordingFileOpener",
            ).containsMatchIn(sessionApi),
            "Missing opted-in recording file opener on the public session",
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            sessionApi.contains("public val channelRepository: ChannelRepository"),
            "Missing channel repository on the public session",
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            sessionApi.contains("public val epgRepository: EpgRepository"),
            "Missing EPG repository on the public session",
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            sessionApi.contains("public val dvrRepository: DvrRepository"),
            "Missing DVR repository on the public session",
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            sessionApi.contains(
                "public val recordingProgressCapability: StateFlow<RecordingProgressCapability>",
            ),
            "Missing recording progress capability on the public session",
        )
    }

    @Test
    fun `every hand written public SDK type is reachable from a public entry point`() {
        val scope = listOf("sdk-android", "sdk-core", "sdk-playback", "sdk-testing", "sdk-media3")
            .map(::productionScope)
            .reduce(KoScope::plus)
        val publicTypes = scope.classesAndInterfacesAndObjects(includeNested = true, includeLocal = false)
            .filter(::isEffectivelyPublic)
            .mapNotNull { declaration ->
                declaration.fullyQualifiedName?.let { name -> name to declaration }
            }
            .toMap()
        val reachable = LinkedHashSet<String>()
        val pending = ArrayDeque<KoClassAndInterfaceAndObjectDeclaration>()

        fun enqueue(declaration: KoClassAndInterfaceAndObjectDeclaration?) {
            declaration ?: return
            val name = declaration.fullyQualifiedName ?: return
            if (name in publicTypes && reachable.add(name)) pending.addLast(declaration)
        }

        scope.functions(includeLocal = false, includeNested = false)
            .filter { function ->
                function.hasPublicOrDefaultModifier &&
                    function.name in setOf(
                        "createTvheadendSession",
                        "createTvheadendArtworkFetcherFactory",
                        "createSubscriptionManager",
                        "createRecordingFileReader",
                        "createTvheadendLiveMediaSource",
                        "createTvheadendPlaybackRecovery",
                        "createTvheadendPlaybackCoordinator",
                        "createTvheadendRenderersFactory",
                        "createTvheadendRecordingDataSourceFactory",
                        "createTvheadendRecordingMediaSource",
                        "createTvheadendRecordingResume",
                        "tvheadendRecordingMediaItem",
                    )
            }
            .forEach { function -> function.referencedPublicTypes().forEach(::enqueue) }
        setOf(
            "at.bernhardberger.tvheadend.sdk.android.TvheadendConnectivity",
            "at.bernhardberger.tvheadend.sdk.android.TvheadendCredentialStore",
            "at.bernhardberger.tvheadend.sdk.android.TvheadendServerProfileStore",
            "at.bernhardberger.tvheadend.sdk.android.TvheadendDiscovery",
            "at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi",
            "at.bernhardberger.tvheadend.sdk.media3.PlaybackRecoveryReason",
            "at.bernhardberger.tvheadend.sdk.media3.TvheadendRecordingException",
            "at.bernhardberger.tvheadend.sdk.core.ChannelService",
            "at.bernhardberger.tvheadend.sdk.core.DvrRecordingFile",
            "at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy",
            "at.bernhardberger.tvheadend.sdk.testing.ScriptedSubscriptionConnection",
            "at.bernhardberger.tvheadend.sdk.testing.SubscriptionBinaryFixture",
            "at.bernhardberger.tvheadend.sdk.testing.FakeChannelRepository",
            "at.bernhardberger.tvheadend.sdk.testing.FakeEpgRepository",
            "at.bernhardberger.tvheadend.sdk.testing.FakeDvrRepository",
        ).forEach { name -> enqueue(publicTypes[name]) }

        while (pending.isNotEmpty()) {
            val declaration = pending.removeFirst()
            declaration.parents(false)
                .flatMap { parent -> parent.referencedPublicTypes() }
                .forEach(::enqueue)
            declaration.classesAndInterfacesAndObjects(includeNested = false, includeLocal = false)
                .filter(::isEffectivelyPublic)
                .forEach(::enqueue)
            declaration.functions(includeNested = false, includeLocal = false)
                .filter { function -> function.hasPublicOrDefaultModifier }
                .flatMap { function -> function.referencedPublicTypes() }
                .forEach(::enqueue)
            declaration.properties(includeNested = false)
                .filter { property -> property.hasPublicOrDefaultModifier }
                .flatMap { property -> property.referencedPublicTypes() }
                .forEach(::enqueue)
            if (declaration is com.lemonappdev.konsist.api.declaration.KoClassDeclaration) {
                declaration.constructors
                    .filter { constructor -> constructor.hasPublicOrDefaultModifier }
                    .flatMap { constructor -> constructor.referencedPublicTypes() }
                    .forEach(::enqueue)
            }
        }

        assertEquals(emptySet<String>(), publicTypes.keys - reachable)
    }

    private fun assertPublicInfrastructure(
        module: String,
        expected: Set<String>,
        unannotatedCount: Int,
    ) {
        val source = productionScope(module).files.joinToString("\n") { file -> file.text }
        val declaration = Regex(
            pattern = "^public\\s+(?:(?:data|sealed|value|fun)\\s+)*(?:annotation\\s+class|enum\\s+class|class|interface|object|fun|const\\s+val|val)\\s+(\\w+)",
            option = RegexOption.MULTILINE,
        )
        val actual = declaration.findAll(source).map { match -> match.groupValues[1] }.toSet()
        val annotatedCount = Regex(
            "@SubscriptionInfrastructureApi(?:\\s+@[^\\n]+)*\\s+public",
        )
            .findAll(source)
            .count()

        assertEquals(expected, actual)
        assertEquals(expected.size - unannotatedCount, annotatedCount)
    }

    private fun productionScope(module: String) =
        Konsist.scopeFromDirectory("$module/src/main/kotlin")

    private fun isEffectivelyPublic(
        declaration: KoClassAndInterfaceAndObjectDeclaration,
    ): Boolean {
        if (!declaration.hasPublicOrDefaultModifier) return false
        var containing: KoBaseDeclaration? =
            (declaration as KoContainingDeclarationProvider).containingDeclaration
        while (containing is KoVisibilityModifierProvider) {
            if (!containing.hasPublicOrDefaultModifier) return false
            containing = (containing as? KoContainingDeclarationProvider)?.containingDeclaration
        }
        return true
    }

    private fun KoFunctionDeclaration.referencedPublicTypes(): List<KoClassAndInterfaceAndObjectDeclaration> =
        returnType?.referencedPublicTypes().orEmpty() +
            parameters.flatMap { parameter -> parameter.referencedPublicTypes() }

    private fun KoPropertyDeclaration.referencedPublicTypes(): List<KoClassAndInterfaceAndObjectDeclaration> =
        type?.referencedPublicTypes().orEmpty()

    private fun KoConstructorDeclaration.referencedPublicTypes(): List<KoClassAndInterfaceAndObjectDeclaration> =
        parameters.flatMap { parameter -> parameter.referencedPublicTypes() }

    private fun KoParameterDeclaration.referencedPublicTypes(): List<KoClassAndInterfaceAndObjectDeclaration> =
        type.referencedPublicTypes()

    private fun KoSourceDeclarationProvider.referencedPublicTypes(): List<KoClassAndInterfaceAndObjectDeclaration> {
        val sourceType = sourceDeclaration?.toPublicTypeOrNull()?.let(::listOf).orEmpty()
        val typeArguments = (this as? KoTypeArgumentDeclaration)?.typeArguments.orEmpty()
            .flatMap { argument -> argument.referencedPublicTypes() }
        return sourceType + typeArguments
    }

    private fun KoTypeDeclaration.referencedPublicTypes(): List<KoClassAndInterfaceAndObjectDeclaration> =
        (this as KoSourceDeclarationProvider).referencedPublicTypes() +
            typeArguments.orEmpty().flatMap { argument -> argument.referencedPublicTypes() }

    private fun KoDeclarationCastProvider.toPublicTypeOrNull(): KoClassAndInterfaceAndObjectDeclaration? =
        if (isClassOrInterfaceOrObject) asClassOrInterfaceOrObjectDeclaration() else null
}
