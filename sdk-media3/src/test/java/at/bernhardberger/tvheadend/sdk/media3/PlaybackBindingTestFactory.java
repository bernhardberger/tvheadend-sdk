package at.bernhardberger.tvheadend.sdk.media3;

import at.bernhardberger.tvheadend.sdk.core.PlaybackBinding;
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult;

final class PlaybackBindingTestFactory {
    private PlaybackBindingTestFactory() {}

    static PlaybackBinding.Live currentLive() {
        return new PlaybackBinding.Live(
                () -> true,
                (consumer, options, continuation) -> SubscriptionOpenResult.NotReady.INSTANCE);
    }
}
