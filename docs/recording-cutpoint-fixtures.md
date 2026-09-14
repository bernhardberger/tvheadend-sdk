# Recording cutpoint fixtures

SDK 0.15.0 supports test-owned cutpoint responses through the public
`TvheadendTestResultFactory.boundCompletedRecordingPlayback` factory. Opt in to
`TvheadendTestingApi` and supply `cutpoints = { result }`, where the suspending
lambda returns a `DvrCutpointsResult`. Applications can use this binding to test
recording markers without a server or internal SDK APIs.

The factory requires a current session observation and a completed recording.
Each cutpoint request checks cancellation and session/recording authority before
invoking the lambda and again after it returns. Generation retirement produces
`ObservationExpired`; removal or replacement of the bound recording produces
`NotReady`. A cancelled request propagates cancellation. A script returning
`ObservationExpired` while the binding remains current is rejected with
`IllegalArgumentException`: scripts cannot manufacture session authority.

Omitting the lambda retains `NotReady`. File opening and progress reporting keep
their existing fixture behavior. This API constructs test bindings, not a media
file or a Player.

The added default argument changes the JVM method signature. Recompile consumers
against 0.15.0; existing Kotlin source calls without the lambda remain valid.
