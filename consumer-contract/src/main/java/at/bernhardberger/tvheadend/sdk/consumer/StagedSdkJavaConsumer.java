package at.bernhardberger.tvheadend.sdk.consumer;

import at.bernhardberger.tvheadend.sdk.android.ServerProfileEditReadResult;
import at.bernhardberger.tvheadend.sdk.android.TvheadendServerProfileStore;
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation;
import at.bernhardberger.tvheadend.sdk.core.EpgRepository;
import at.bernhardberger.tvheadend.sdk.core.EpgSearchRequest;
import at.bernhardberger.tvheadend.sdk.core.EpgSearchResult;
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
