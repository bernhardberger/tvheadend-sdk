package at.bernhardberger.tvheadend.sdk.consumer;

import at.bernhardberger.tvheadend.sdk.android.ServerProfileEditReadResult;
import at.bernhardberger.tvheadend.sdk.android.TvheadendServerProfileStore;
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation;
import at.bernhardberger.tvheadend.sdk.core.EpgRepository;
import at.bernhardberger.tvheadend.sdk.core.EpgSearchRequest;
import at.bernhardberger.tvheadend.sdk.core.EpgSearchResult;
import at.bernhardberger.tvheadend.sdk.media3.TvheadendPlaybackCoordinator;
import at.bernhardberger.tvheadend.sdk.media3.PlaybackOutcomeCategory;
import at.bernhardberger.tvheadend.sdk.media3.PlaybackTargetDisposition;
import at.bernhardberger.tvheadend.sdk.media3.PlaybackTargetResult;
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftCommandDisposition;
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftCommandResult;
import at.bernhardberger.tvheadend.sdk.playback.LiveFrontendDiagnostics;
import at.bernhardberger.tvheadend.sdk.playback.LiveFrontendState;
import at.bernhardberger.tvheadend.sdk.playback.LiveQueueDiagnostics;
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionDiagnostics;
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionSource;
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue;
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssueCategory;
import kotlin.coroutines.Continuation;

public final class StagedSdkJavaConsumer {
    private StagedSdkJavaConsumer() {}

    public static Object loadServerProfileForEditing(
            TvheadendServerProfileStore store,
            Continuation<? super ServerProfileEditReadResult> continuation) {
        return store.loadProfileForEditing(continuation);
    }

    public static Object searchEpg(
            EpgRepository repository,
            CurrentSessionObservation currentSession,
            EpgSearchRequest request,
            Continuation<? super EpgSearchResult> continuation) {
        return repository.search(currentSession, request, continuation);
    }

    public static EpgSearchRequest epgSearchRequest(String query) {
        return EpgSearchRequest.createFromSeconds(
                query,
                true,
                1L,
                2L,
                0x20L,
                "eng",
                60L,
                3_600L);
    }

    public static int searchResultSize(EpgSearchResult result) {
        if (result instanceof EpgSearchResult.Available available) {
            return available.getEvents().size();
        }
        return 0;
    }

    public static String currentLiveServiceName(TvheadendPlaybackCoordinator coordinator) {
        LiveSubscriptionDiagnostics diagnostics = coordinator.getLiveDiagnostics().getValue();
        LiveSubscriptionSource source = diagnostics == null ? null : diagnostics.getSource();
        return source == null ? null : source.getServiceName();
    }

    public static Double currentLiveSignalPercent(TvheadendPlaybackCoordinator coordinator) {
        LiveSubscriptionDiagnostics diagnostics = coordinator.getLiveDiagnostics().getValue();
        LiveFrontendDiagnostics frontend = diagnostics == null ? null : diagnostics.getFrontend();
        return frontend == null ? null : frontend.getRelativeSignalPercent();
    }

    public static boolean currentLiveFrontendLocked(TvheadendPlaybackCoordinator coordinator) {
        LiveSubscriptionDiagnostics diagnostics = coordinator.getLiveDiagnostics().getValue();
        LiveFrontendDiagnostics frontend = diagnostics == null ? null : diagnostics.getFrontend();
        LiveFrontendState state = frontend == null ? null : frontend.getState();
        return state != null && state.getLocked();
    }

    public static Long currentLiveQueuePackets(TvheadendPlaybackCoordinator coordinator) {
        LiveSubscriptionDiagnostics diagnostics = coordinator.getLiveDiagnostics().getValue();
        LiveQueueDiagnostics queue = diagnostics == null ? null : diagnostics.getQueue();
        return queue == null ? null : queue.getPacketCount();
    }

    public static Long currentLiveQueueSpanMicroseconds(TvheadendPlaybackCoordinator coordinator) {
        LiveSubscriptionDiagnostics diagnostics = coordinator.getLiveDiagnostics().getValue();
        LiveQueueDiagnostics queue = diagnostics == null ? null : diagnostics.getQueue();
        return queue == null ? null : queue.getMediaSpanMicroseconds();
    }

    public static boolean targetStarted(PlaybackTargetResult result) {
        return result.isStarted() &&
                result.getDisposition() == PlaybackTargetDisposition.STARTED;
    }

    public static boolean isExactStartedResult(PlaybackTargetResult result) {
        return result == PlaybackTargetResult.STARTED;
    }

    public static boolean targetMayChange(PlaybackTargetResult result) {
        return result.isTransient() &&
                result.getCategories().contains(PlaybackOutcomeCategory.TRANSIENT);
    }

    public static boolean timeshiftAccepted(TimeshiftCommandResult result) {
        return result.isAccepted() &&
                result.getDisposition() == TimeshiftCommandDisposition.ACCEPTED;
    }

    public static boolean isExactAcceptedResult(TimeshiftCommandResult result) {
        return result == TimeshiftCommandResult.ACCEPTED;
    }

    public static boolean timeshiftNeedsConfigurationOrAccess(TimeshiftCommandResult result) {
        return result.isConfigurationOrAccessRelated() &&
                result.getCategories().contains(PlaybackOutcomeCategory.CONFIGURATION_OR_ACCESS);
    }

    public static boolean targetHasOtherClassification(PlaybackTargetResult result) {
        return result.isTerminal() ||
                result.isUnsupported() ||
                result.isConfigurationOrAccessRelated() ||
                result.isOutcomeUncertain();
    }

    public static boolean timeshiftHasOtherClassification(TimeshiftCommandResult result) {
        return result.isTerminal() ||
                result.isUnsupported() ||
                result.isOutcomeUncertain();
    }

    public static boolean issueNeedsConfigurationOrAccess(SubscriptionIssue issue) {
        return issue.isConfigurationOrAccessRelated() &&
                issue.getCategory() == SubscriptionIssueCategory.CONFIGURATION_OR_ACCESS;
    }

    public static boolean hasExpectedEditableFields(ServerProfileEditReadResult result) {
        if (result == ServerProfileEditReadResult.Missing.INSTANCE ||
                result == ServerProfileEditReadResult.Unavailable.INSTANCE) {
            return true;
        }
        if (result instanceof ServerProfileEditReadResult.Anonymous anonymous) {
            return !anonymous.getHost().isEmpty() && anonymous.getPort() > 0;
        }
        if (result instanceof ServerProfileEditReadResult.Password password) {
            return !password.getHost().isEmpty() &&
                    password.getPort() > 0 &&
                    !password.getUsername().isEmpty() &&
                    !password.getPassword().isEmpty();
        }
        throw new IllegalArgumentException("Unknown profile edit result");
    }
}
