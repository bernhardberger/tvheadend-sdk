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
            "public\\s+(?:(?:data|sealed|value|annotation)\\s+)*(?:class|interface|enum\\s+class|object)\\s+(\\w+)",
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
            "RetainedMetadataAuthority",
            "Empty",
            "Unknown",
            "Denied",
            "Synchronizing",
            "Current",
            "Stale",
            "SessionObservation",
            "SessionGenerationIdentity",
            "CurrentSessionObservation",
            "PlaybackBinding",
            "Live",
            "Recording",
            "PlaybackBindingResult",
            "Bound",
            "TargetUnavailable",
            "RecordingPlaybackAdmission",
            "Completed",
            "GrowingStartOverOnly",
            "GrowingDeferred",
            "EpgRating",
            "EpgEpisode",
            "EpgEvent",
            "EpgCoverage",
            "EpgCoveragePolicy",
            "EpgCoverageAcquisitionResult",
            "CoveredWithData",
            "CoveredEmpty",
            "Ineligible",
            "ObservationExpired",
            "EpgSearchRequest",
            "EpgSearchResult",
            "InvalidQuery",
            "ConnectionChanged",
            "EpgSnapshot",
            "EpgRepositoryState",
            "EpgRepository",
            "TvheadendSession",
            "ServerProfile",
            "ServerAuthentication",
            "ServerProfileStore",
            "ServerProfileAuthenticationMode",
            "ServerProfileReadResult",
            "Missing",
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
            "TvheadendTestingApi",
            "SessionGenerationTestAuthority",
            "TvheadendTestResultFactory",
        )

        assertEquals(expected, actual)
    }

    @Test
    fun `public suspending SDK calls use typed outcomes or lifecycle Unit`() {
        val publicApi = listOf(
            "src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/TvheadendSession.kt",
            "src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/DvrRepository.kt",
            "src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/PlaybackBinding.kt",
            "src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/EpgRepository.kt",
            "src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/Artwork.kt",
            "src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/ServerProfileStore.kt",
            "../sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendPlaybackCoordinator.kt",
        ).joinToString(" ") { path -> File(path).readText() }.replace(Regex("\\s+"), " ")
        val expectedSignatures = listOf(
            "public suspend fun connect(profile: ServerProfile): SessionCommandResult",
            "public suspend fun retry(): SessionCommandResult",
            "public suspend fun disconnect()",
            "public suspend fun shutdown()",
            "public suspend fun awaitCurrentSession( replaced: CurrentSessionObservation? = null, ): CurrentSessionObservation",
            "public suspend fun getStreamProfiles( currentSession: CurrentSessionObservation, ): StreamProfilesResult",
            "public suspend fun search( currentSession: CurrentSessionObservation, request: EpgSearchRequest, ): EpgSearchResult",
            "public suspend fun acquireCoverage( currentSession: CurrentSessionObservation, channelId: ChannelId, through: Instant, ): EpgCoverageAcquisitionResult",
            "public suspend fun loadArtwork( currentSession: CurrentSessionObservation, artworkId: ArtworkId, ): ArtworkLoadResult",
            "public suspend fun loadProfile(): ServerProfileReadResult",
            "public suspend fun storeAnonymous( host: String, port: Int = 9_982, ): ServerProfileReadResult",
            "public suspend fun storePassword( host: String, port: Int = 9_982, username: String, password: String, ): ServerProfileReadResult",
            "public suspend fun clearProfile(): ServerProfileReadResult",
            "public suspend fun scheduleEntry( currentSession: CurrentSessionObservation, request: DvrScheduleRequest, ): DvrMutationResult<DvrEntryId>",
            "public suspend fun updateEntry( currentSession: CurrentSessionObservation, id: DvrEntryId, update: DvrEntryUpdate, ): DvrMutationResult<Unit>",
            "public suspend fun stopEntry( currentSession: CurrentSessionObservation, id: DvrEntryId, ): DvrMutationResult<Unit>",
            "public suspend fun cancelEntry( currentSession: CurrentSessionObservation, id: DvrEntryId, ): DvrMutationResult<Unit>",
            "public suspend fun deleteEntry( currentSession: CurrentSessionObservation, id: DvrEntryId, ): DvrMutationResult<Unit>",
            "public suspend fun createAutorecRule( currentSession: CurrentSessionObservation, request: AutorecRuleCreate, ): DvrMutationResult<AutorecRuleId>",
            "public suspend fun updateAutorecRule( currentSession: CurrentSessionObservation, id: AutorecRuleId, update: AutorecRuleUpdate, ): DvrMutationResult<Unit>",
            "public suspend fun deleteAutorecRule( currentSession: CurrentSessionObservation, id: AutorecRuleId, ): DvrMutationResult<Unit>",
            "public suspend fun createTimerecRule( currentSession: CurrentSessionObservation, request: TimerecRuleCreate, ): DvrMutationResult<TimerecRuleId>",
            "public suspend fun updateTimerecRule( currentSession: CurrentSessionObservation, id: TimerecRuleId, update: TimerecRuleUpdate, ): DvrMutationResult<Unit>",
            "public suspend fun deleteTimerecRule( currentSession: CurrentSessionObservation, id: TimerecRuleId, ): DvrMutationResult<Unit>",
            "public suspend fun open( consumer: SubscriptionEventConsumer, options: SubscriptionOptions, ): SubscriptionOpenResult",
            "public suspend fun cutpoints(): DvrCutpointsResult",
            "public suspend fun openRecording(): RecordingFileResult<RecordingFile>",
            "public suspend fun reportProgress( growingLease: GrowingRecordingFileLease?, progress: DvrPlaybackProgress, ): DvrProgressResult",
            "public suspend fun run()",
            "public suspend fun withLifetime( drainTimeout: Duration, block: suspend CoroutineScope.(TvheadendPlaybackCoordinator) -> Unit, ): PlaybackShutdownResult",
            "public suspend fun setLiveTarget( binding: PlaybackBinding.Live, options: LivePlaybackOptions = LivePlaybackOptions(), ): PlaybackTargetResult",
            "public suspend fun setRecordingTarget( binding: PlaybackBinding.Recording, start: RecordingPlaybackStart = RecordingPlaybackStart.RESUME, ): PlaybackTargetResult",
            "public suspend fun seekTimeshift(offset: Duration): TimeshiftCommandResult",
            "public suspend fun returnToLive(): TimeshiftCommandResult",
            "public suspend fun pauseTimeshift(): TimeshiftCommandResult",
            "public suspend fun resumeTimeshift(): TimeshiftCommandResult",
            "public suspend fun stop(): PlaybackStopResult",
            "public suspend fun shutdown(drainTimeout: Duration): PlaybackShutdownResult",
            "public suspend fun shutdown(drainTimeout: Duration): PlaybackShutdownResult",
            "public suspend fun shutdownMillis(drainTimeoutMillis: Long): PlaybackShutdownResult",
            "public suspend fun join(): Unit",
        )

        assertEquals(expectedSignatures.size, Regex("public suspend fun ").findAll(publicApi).count())
        expectedSignatures.forEach { signature ->
            org.junit.jupiter.api.Assertions.assertTrue(
                publicApi.contains(signature),
                "Missing typed public suspending signature",
            )
        }
        assertEquals(
            2,
            Regex(
                Regex.escape(
                    "public suspend fun shutdown(drainTimeout: Duration): PlaybackShutdownResult",
                ),
            ).findAll(publicApi).count(),
        )
        assertTrue("public sealed interface PlaybackCoordinatorLifetime" in publicApi)
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
            "SubscriptionIssueCategory",
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
            "LiveSubscriptionSource",
            "LiveFrontendState",
            "LiveFrontendDiagnostics",
            "LiveQueueDiagnostics",
            "LiveSubscriptionDiagnostics",
            "SubscriptionOpenResult",
            "SubscriptionCloseResult",
            "ActiveSubscription",
            "SubscriptionOpener",
            "SubscriptionManager",
            "createSubscriptionManager",
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
            "FakeArtworkLoader",
            "FakeDvrRepository",
            "FakeEpgRepository",
            "FakePlaybackApi",
            "FakeServerProfileStore",
            "FakeServerProfileStoreCall",
            "FakeSessionCall",
            "FakeSessionObservation",
            "FakeTvheadendSession",
            "ScriptedSubscriptionCall",
            "ScriptedSubscriptionConnection",
            "ScriptedSubscriptionRegistration",
            "SubscriptionBinaryFixture",
        )
        val expectedMedia3 = setOf(
            "LiveTimeshiftState",
            "TimeshiftCommandDisposition",
            "PlaybackOutcomeCategory",
            "TimeshiftCommandResult",
            "PlaybackShutdownResult",
            "PlaybackCoordinatorLifetime",
            "PlaybackRecoveryPolicy",
            "PlaybackRecoveryReason",
            "PlaybackStopResult",
            "PlaybackTargetDisposition",
            "PlaybackTargetResult",
            "RecordingPlaybackStart",
            "LivePlaybackOptions",
            "TvheadendPlaybackRecovery",
            "TvheadendPlaybackCoordinator",
            "TvheadendRecordingException",
            "createTvheadendPlaybackRecovery",
            "createTvheadendPlaybackCoordinator",
            "createTvheadendRenderersFactory",
        )
        val expectedAndroid = setOf(
            "CredentialOperationResult",
            "CredentialReadResult",
            "ServerProfileEditReadResult",
            "TvheadendDiscovery",
            "DiscoveredTvheadendServer",
            "TvheadendDiscoveryState",
            "TvheadendDiscoveryFailure",
            "TvheadendConnectivity",
            "TvheadendConnectivityStatus",
            "TvheadendCredentialStore",
            "TvheadendServerProfileStore",
            "TvheadendArtwork",
            "TvheadendArtworkLoadException",
            "ComponentRegistry",
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
        assertPublicInfrastructure("sdk-playback", expectedPlayback, unannotatedCount = 9)
        assertPublicInfrastructure("sdk-testing", expectedTesting, unannotatedCount = 9)
        val fakeSessionSource = File(
            repositoryRoot,
            "sdk-testing/src/main/kotlin/at/bernhardberger/tvheadend/sdk/testing/FakeTvheadendSession.kt",
        ).readText()
        val fakePlaybackFunctions = Regex("^[\\t ]*@FakePlaybackApi\\s+public fun (\\w+)", RegexOption.MULTILINE)
            .findAll(fakeSessionSource)
            .map { match -> match.groupValues[1] }
            .toSet()
        assertEquals(
            setOf(
                "scriptLivePlaybackSuccess",
                "scriptRecordingPlaybackSuccess",
                "scriptLivePlaybackFailure",
                "scriptRecordingPlaybackFailure",
            ),
            fakePlaybackFunctions,
        )
        assertPublicInfrastructure("sdk-media3", expectedMedia3, unannotatedCount = 18)

        val coordinatorApi = File(
            "../sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/" +
                "TvheadendPlaybackCoordinator.kt",
        ).readText().replace(Regex("\\s+"), " ")
        assertTrue(
            coordinatorApi.contains(
                "public fun createTvheadendPlaybackCoordinator( player: ExoPlayer,",
            ) && !coordinatorApi.contains(
                "public fun createTvheadendPlaybackCoordinator( session:",
            ),
            "Playback coordinator creation must not retain unused session authority",
        )

        val sessionApi = java.io.File(
            "src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/TvheadendSession.kt",
        ).readText().replace(Regex("\\s+"), " ")
        org.junit.jupiter.api.Assertions.assertTrue(
            !sessionApi.contains("public val subscriptions:") &&
                !sessionApi.contains("public val recordings:"),
            "Raw playback openers must not be exposed by the public session",
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            sessionApi.contains(
                "public fun bindLivePlayback( currentSession: CurrentSessionObservation, " +
                    "channelId: ChannelId, ): PlaybackBindingResult<PlaybackBinding.Live>",
            ) && sessionApi.contains(
                "public fun bindRecordingPlayback( currentSession: CurrentSessionObservation, " +
                    "recordingId: DvrEntryId, ): PlaybackBindingResult<PlaybackBinding.Recording>",
            ),
            "Public playback authority must be created from one current-session observation",
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            sessionApi.contains("public val observation: StateFlow<SessionObservation>"),
            "Missing aggregate observation on the public session",
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
            !sessionApi.contains("public val state: StateFlow<SessionState>") &&
                !sessionApi.contains("public val channelRepository:") &&
                !sessionApi.contains("public val recordingProgressCapability:"),
            "Independent public session observation flows were not removed",
        )
        val artworkApi = File(
            "../sdk-android/src/main/kotlin/at/bernhardberger/tvheadend/sdk/android/TvheadendArtwork.kt",
        ).readText().replace(Regex("\\s+"), " ")
        assertTrue(
            artworkApi.contains(
                "public fun create( session: TvheadendSession, " +
                    "currentSession: CurrentSessionObservation, source: String?, ): TvheadendArtwork?",
            ) && artworkApi.contains(
                "public fun ComponentRegistry.Builder.addTvheadendArtwork(): ComponentRegistry.Builder",
            ) && artworkApi.contains(
                "public class TvheadendArtworkLoadException( public val failure: ArtworkFailure, )",
            ),
            "Artwork integration must retain provenance, register atomically, and preserve failures",
        )
        assertEquals(1, Regex("public fun create\\(").findAll(artworkApi).count())
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
                        "addTvheadendArtwork",
                        "createSubscriptionManager",
                        "createRecordingFileReader",
                        "createTvheadendPlaybackRecovery",
                        "createTvheadendPlaybackCoordinator",
                        "createTvheadendRenderersFactory",
                    )
            }
            .forEach { function -> function.referencedPublicTypes().forEach(::enqueue) }
        setOf(
            "at.bernhardberger.tvheadend.sdk.android.TvheadendConnectivity",
            "at.bernhardberger.tvheadend.sdk.android.TvheadendCredentialStore",
            "at.bernhardberger.tvheadend.sdk.android.TvheadendServerProfileStore",
            "at.bernhardberger.tvheadend.sdk.android.TvheadendDiscovery",
            "at.bernhardberger.tvheadend.sdk.android.TvheadendArtwork",
            "at.bernhardberger.tvheadend.sdk.android.TvheadendArtworkLoadException",
            "at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi",
            "at.bernhardberger.tvheadend.sdk.media3.PlaybackRecoveryReason",
            "at.bernhardberger.tvheadend.sdk.media3.TvheadendRecordingException",
            "at.bernhardberger.tvheadend.sdk.core.TvheadendSession",
            "at.bernhardberger.tvheadend.sdk.core.EpgRepository",
            "at.bernhardberger.tvheadend.sdk.core.DvrRepository",
            "at.bernhardberger.tvheadend.sdk.core.ChannelService",
            "at.bernhardberger.tvheadend.sdk.core.DvrRecordingFile",
            "at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy",
            "at.bernhardberger.tvheadend.sdk.testing.ScriptedSubscriptionConnection",
            "at.bernhardberger.tvheadend.sdk.testing.SubscriptionBinaryFixture",
            "at.bernhardberger.tvheadend.sdk.testing.FakeSessionObservation",
            "at.bernhardberger.tvheadend.sdk.testing.FakeTvheadendSession",
            "at.bernhardberger.tvheadend.sdk.testing.FakeServerProfileStore",
            "at.bernhardberger.tvheadend.sdk.core.SessionGenerationTestAuthority",
            "at.bernhardberger.tvheadend.sdk.core.TvheadendTestResultFactory",
            "at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi",
            "at.bernhardberger.tvheadend.sdk.testing.FakePlaybackApi",
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
            "^@(?:SubscriptionInfrastructureApi|FakePlaybackApi)(?:\\s+@[^\\n]+)*\\s+public",
            RegexOption.MULTILINE,
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
