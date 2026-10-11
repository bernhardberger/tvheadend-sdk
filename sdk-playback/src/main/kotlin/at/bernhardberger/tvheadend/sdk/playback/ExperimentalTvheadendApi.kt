package at.bernhardberger.tvheadend.sdk.playback

/** API outside the stable compatibility promise until this opt-in requirement is removed. */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This TVHeadend SDK API is experimental and may change in any minor release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalTvheadendApi
