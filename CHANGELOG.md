# Changelog

## [0.27.0]

Released 2026-10-12.

### Added

- Experimental `DvrRepository.resetResume(currentSession, id)` clears resume in
  one command while preserving play count, sharing watch-state command rules.
- Experimental `DvrRepository.updateWatchState(currentSession, id, watched)` marks
  watched or unwatched and clears resume in one command. A successful reply is
  accepted, not confirmation of changed fields; asynchronous metadata remains
  authoritative. `FakeDvrRepository` scripts results and captures redacted requests.
- Experimental SDK-owned `TvheadendLoadControl` and `TvheadendLiveStartBuffer`
  configure application-created Player builders without a Media3 unstable opt-in.
  Live startup thresholds can be updated across threads; the 2 s default,
  1.5 s ordinary rebuffer and existing recording policy are preserved.
- The experimental coordinator overload accepts the same load-control handle
  used to build its borrowed Player. Server skips use the live-start threshold
  for one restart, including an existing rebuffer, with a 3 s grace measured
  from command completion and target/period-fenced cancellation and retirement.
  A restart mark stays cancellable after acknowledgement while a paused seek
  refills.
- Experimental `TvheadendPlaybackCoordinator.recoverRecordingTarget` restores a
  caller-captured recording position through the existing resume machinery.
  `pendingRecordingRecoveryPosition` exposes the owed position for repeated retry
  capture, with target fencing and the existing bounded progress-report hold.
  Known-duration recovery falls back before installation; unknown-duration recovery
  drops its seek at the new timeline cutoff. In-install viewer seeks cancel recovery,
  and failed replacement restores the previous target's settled pending position.
- Experimental observation-side `MetadataRefreshTimes` and per-channel EPG query
  receipt times distinguish retained content from recent successful acceptance.
  Unchanged replies preserve snapshot identity and hashes; failed or retired
  queries do not advance freshness. Observation factories and fakes accept times.
- Experimental `LiveRetuneBudget` provides optional atomic live-retune admission
  with the 0/2/5/10/20-second schedule, a 60-second refill since the previous
  admission, explicit reset and an injectable monotonic time source. Applications
  retain delay scheduling, target installation and exhaustion policy.
- Experimental `DvrRecordingFile.streams` exposes per-file codec, language and
  independent width/height observations without opening playback. Missing and
  empty metadata remain distinct, and unknown streams retain their positions.
  Streams participate in recording-file equality and hash codes. `sdk-testing`
  adds configurable recording fixtures, including audio-only metadata.
- Experimental `Channel.mediaKind` and `ChannelService.mediaKind` derive the
  open `ChannelMediaKind` classification from service content. TV takes
  precedence over radio; OTHER means the server classified services as neither
  (possibly unscanned), while UNKNOWN means insufficient or unrecognized
  evidence. `sdk-testing` adds TV/radio/unknown catalog fixtures.

### Changed

- **BREAKING:** `DvrRepository` has new abstract `updateWatchState` and `resetResume` members.
  Implementors must implement both despite their experimental annotations.
- Persist refresh times alongside cached channel and EPG content. The EPG cache
  format moves to EPG3; previous EPG caches are discarded and rebuilt automatically.
  The first offline start has no cached guide until it is refetched. Cached catalog
  content is kept, with unknown refresh times for older cache files.

### Documentation

- Clarified that multiple coordinators can share one session, with separate
  application-owned Players and independent coordinator lifetimes.

## [0.26.0]

Released 2026-10-12.

### Added

- `DvrEntry.folder` provides unmodifiable storage-root-relative folder components
  for display and archive grouping, without exposing file-system paths.
- `DvrEntry.create` accepts a validated, defensively copied `folder` for
  application-built entries without server paths.
- `TvheadendTestResultFactory.completedRecordingAdmission` and
  `growingRecordingAdmission` create test-only admission values; the growing
  helper also requires `ExperimentalTvheadendApi` opt-in.
- Shared open `TvheadendFailure` with transient, fresh-observation, access and
  unsupported predicates; transient does not authorize automatic retries. See
  the [0.26 API shape](docs/api-shape-0.26.md) for the migration design.

### Changed

- **BREAKING:** `SessionRecoveryDisposition`, `DvrEntryState`,
  `DvrSubscriptionError`, `DvrCutpointAction`, `DvrChangeKind`,
  `SubscriptionStreamType`, `PlaybackShutdownResult`, `PlaybackRecoveryReason`
  and `TvheadendDiscoveryFailure` are open sets, not enums. Compare their
  SCREAMING_CASE values with a fallback; replace enum iteration and parsing.
- **BREAKING:** `SessionFailure` and `PlaybackStopResult` are value-equal classes
  with open `kind` and `outcome`. Use SCREAMING_CASE constants and lowerCamel
  factories (`synchronizationFailed`, `stopped`, `retiredAfterDeparture`,
  `playerUnavailable`); replace nested-case type checks with value comparisons.
- **BREAKING:** `DvrMutationResult`, `DvrProgressResult`, `DvrCutpointsResult`,
  `StreamProfilesResult`, `EpgSearchResult`, `RecordingFileResult` and
  `SubscriptionOperationResult` now use value-equal `Failed(failure)` cases.
  Match `is Failed`, then inspect `TvheadendFailure` with a fallback.
- **BREAKING:** DVR mutation and progress `Failed` constructors also require
  `mayHaveApplied`: false only for provably unsent or explicitly refused commands.
  Supply this stage evidence in fakes and check it before deciding to retry.
- **BREAKING:** `EpgSearchResult.InvalidQuery` becomes `SERVER_REJECTED`, and
  interrupted stream-profile discovery reports `CONNECTION_CHANGED`.
  Match these shared failure values and obtain a fresh observation when required.
- **BREAKING:** DVR weekdays use `Set<DayOfWeek>`, timerec times `LocalTime`,
  autorec times `AutorecTime`, and padding `Duration`; supply whole minutes.
  `AnyTime` writes -1; creating a rule with either window bound set to any time
  makes both bounds any time. Use `DvrPriority`, `DuplicateDetection` and
  `BroadcastType` values, not wire integers; requests reject `UNKNOWN`.
  `DvrEntry.duplicate` is `Boolean?`; entry and EPG artwork use `ArtworkId?`.
  Migrate request and fake fields to these types; update nulls still mean unchanged.
- **BREAKING:** `RecordingPlaybackAdmission.GrowingStartOverOnly` is now `Growing`.
  Rename matches and opt in with `@OptIn(ExperimentalTvheadendApi::class)` for it,
  `GrowingRecordingFileReader` and `GrowingRecordingFileLease`; completed-recording
  playback remains stable.
- **BREAKING:** `createTvheadendSession` throws `IllegalStateException` for
  conflicting policies. Pass equal configuration or omit policies to reuse an
  owner, including during shutdown. Policies compare by value; cache roots use
  normalized absolute paths resolved at creation, without resolving symlinks.
- **BREAKING:** interrupted DVR mutation, progress and cutpoint operations report
  `CONNECTION_CHANGED` on generation retirement; disconnect/shutdown reports
  `NOT_READY` for current unsent work, or `OBSERVATION_EXPIRED` for stale input.
  Possibly sent work reports `CONNECTION_CHANGED`; refresh observations and check
  `mayHaveApplied` before retrying mutations or progress updates.

### Removed

- **BREAKING:** `SessionOperationFailure`, `ArtworkFailure`, `RecordingFileFailure`
  and `SubscriptionOperationFailure` are removed. Use `TvheadendFailure` in
  `SessionFailure.operationFailure`, artwork `Unavailable`/load exceptions, file
  failures and subscription seek/terminal failures; import the core
  `recoveryDisposition` extension for the former session-operation mapping,
  with `EXPLICIT_RETRY` as its fallback.
- **BREAKING:** `contentType` is removed from entry/event models and factories.
  Use `contentGenre` instead.
- **BREAKING:** DVR `owner`, `creator` and file `path` are no longer public or
  accepted by `DvrEntry.create`, `AutorecRule.create` and `TimerecRule.create`.
  Drop those arguments; use `DvrRecordingFile.create` without a path instead of
  its now-internal constructor or `copy`. Use `DvrEntry.folder` for grouping and
  SDK file access for playback.
- **BREAKING:** `TvheadendCredentialStore`, `CredentialReadResult`,
  `CredentialOperationResult` and legacy v1 storage are removed.
  Move credential persistence to `TvheadendServerProfileStore`.
- **BREAKING:** `PlaybackTargetResult.GROWING_RECORDING_RESUME_UNSUPPORTED` and
  `EpgCoverageBatchSettlement.channelIdValue` are removed; drop the obsolete
  result branch and use `channelId` instead of its raw-value shortcut.
- **BREAKING:** `TimeshiftTestFixture` now requires
  `@OptIn(TvheadendTestingApi::class)`. Its `sdk-media3` coordinate and
  `at.bernhardberger.tvheadend.sdk.media3.testing` package are unchanged.

### Fixed

- Autorec "any time" values now read as `AnyTime`, rather than null: negative
  values and values of 1440 or more are accepted, and writes use -1.
- Timerec rules with unset or out-of-day times are retained with null time fields.
- Invalid metadata cache roots throw a fixed-message `IllegalArgumentException`
  without a cause, preventing the path from leaking through exception diagnostics.

## [0.25.0]

Released 2026-10-11.

### Added

- Offline kotlinx-benchmark/JMH scenarios for EPG ingestion, updates and metadata
  reader contention, with allocation measurements and synthetic guide sizes.
- Mixed EPG trace benchmarks combining changed and duplicate updates, history
  expiry, concurrent now/next browsing and bounded retained snapshots.
- Cache restore/resync lifecycle benchmarks with opt-in JDK Flight Recorder
  after-GC heap checkpoints and bounded consumer snapshot retention.
- Android recorded-packet allocation diagnostics for four elementary-stream readers.

### Changed

- Pre-size the EPG event-ID index to reduce update-publication allocation by
  25–37% in the synthetic guide benchmarks, without changing lookup semantics.
- Reuse the immutable EPG snapshot for identical live-event updates while still
  advancing query authority, superseding history and pruning expired history.
- Cache immutable EPG event hashes to reduce repeated guide-publication work;
  equality and hash-collision handling remain unchanged.
- Reuse bounded Media3 packet scratch buffers to reduce steady reader allocation,
  preserving packet copies, logical lengths and finite-IDR validation.
- Build the EPG event-ID index with one insertion per event, reducing temporary
  allocation while preserving ambiguity for duplicate IDs.

## [0.24.0]

Released 2026-10-11.

### Added

- `SubscriptionDiagnostics.consumerDrainTimedOut` reports that teardown
  cancelled consumer code still running when a teardown grace period expired.
- `TvheadendPlaybackCoordinator.playerCleanupPending` reports coordinator items
  whose Player stop or removal could not be confirmed. The coordinator keeps
  their exact sources and retries their cleanup in `stop()`, `setLiveTarget()`,
  `setRecordingTarget()` and live-buffer retirement, and once more when
  `shutdown()` runs or `run()` is cancelled before abandoning it. It
  removes only its own items and stops the Player only while one of them is
  current, so application items keep their position and play intent. Pending
  items never report DVR progress, errors or recovery.

### Changed

- **BREAKING:** applications no longer reconcile the Player themselves after
  `PlaybackTargetResult.PLAYER_UNAVAILABLE`, `PlaybackStopResult.PlayerUnavailable`
  or `LIVE_BUFFER_EXHAUSTED_PLAYER_CLEANUP_FAILED`; they retry `stop()` or the
  target once the fault clears, and the coordinator cleans up its own items.
  While cleanup keeps failing, installation returns `PLAYER_UNAVAILABLE`
  without creating a source, `stop()` returns `PlayerUnavailable` and
  `shutdown()` returns `PLAYER_UNAVAILABLE`, even with no target installed.
  `AlreadyStopped` means no target and no pending cleanup. See
  [pausing live TV without timeshift](docs/consumer-guide.md#pausing-live-tv-without-timeshift).

- The `api` dependency `kotlinx-coroutines-core` moves from 1.10.2 to 1.11.0, so
  consumers resolve 1.11.0 transitively. The build now uses Gradle 9.8.1 and
  Android Gradle Plugin 9.4.1.
- The `sdk-media3` dependencies move from Media3 1.11.0 to 1.11.1, a patch
  release; the adapted `H264Reader` is unchanged upstream.
- The `api` dependency `coil-core` moves from 3.5.0 to 3.6.3. Coil 3.6.3's AAR
  metadata requires compileSdk 37, so the Android modules now compile against
  API 37. Their AAR metadata records that as the minimum, so consumers of
  `sdk-android` or `sdk-media3` must use compileSdk 37 or newer; the JVM modules
  are unaffected. targetSdk stays 36.
- **BREAKING:** `sdk-android` and `sdk-media3` raise minSdk from 24 to 28.
- `sdk-android` publishes a dependency constraint that raises Okio to 3.18.2.
  Coil 3.6.3 brings Okio 3.18.1, which encodes base64 without padding for code
  compiled against Okio releases before 3.18, such as HTTP Basic credentials
  built by older OkHttp versions. Maven consumers do not inherit POM
  `dependencyManagement`, so they must pin `okio` and `okio-jvm` 3.18.2
  themselves.
- **BREAKING:** `sdk-media3` no longer bundles the Media3 FFmpeg audio decoder or
  its native libraries and no longer publishes the `ffmpeg-sources` classifier.
  Software audio decoding is application policy: an audio track with neither a
  platform decoder nor a passthrough path is reported as unsupported. Apps that
  need software AC-3, E-AC-3 or MP3 decoding bundle their own Media3 FFmpeg
  audio extension, which the SDK renderers factory picks up automatically; see
  [software audio decoding](docs/consumer-guide.md#software-audio-decoding).
- **BREAKING:** `TvheadendPlaybackCoordinator` attributes Player state to a
  target only while the Player's current item is the one it installed. When the
  application replaces, clears or advances past that item, the coordinator stops
  reporting DVR progress, errors and recovery for it and never reclaims it;
  `stop()`, `shutdown()` and the next target retire it without stopping the
  Player or clearing the application's items, and remove only coordinator-owned
  items from a mixed playlist. `stop()` then returns the new
  `PlaybackStopResult.RetiredAfterDeparture`, so `is Stopped` checks no longer
  match it. If the application returned to the coordinator's item and it is
  current, all three paths stop the Player and remove that item, without the
  coordinator reclaiming reporting; `stop()` then returns `Stopped`. `livePlaybackObservation` reports the new
  `LivePlaybackObservation.Departed` instead of `Active`, so `as Active` casts can
  fail; `timeshiftState`, `subscriptionIssue`, `liveDiagnostics` and timeshift
  commands keep following the departed target until it is retired. Automatic
  advance from a recording to an application item counts as its natural end. A
  recording replaced before `stop()` gets one closing `ORDERLY` report from the
  last position observed while the coordinator owned it, never marking it
  watched and never below a pending saved resume position. An application
  replacing the item during a live or recording installation gets the new
  `PlaybackTargetResult.DEPARTED` (disposition `PlaybackTargetDisposition.DEPARTED`,
  also through `LivePlaybackTargetResult.Bound.result`) instead of `STARTED`,
  without the coordinator preparing its item or restoring the previous target over
  it. Its `isStarted` is false, but the target stays installed until `stop()`,
  replacement or `shutdown()`.
  `TvheadendPlaybackRecovery` no longer re-enables audio on every live target; it
  only restores audio it disabled itself and yields when the application changes
  track selection. See
  [ownership and shutdown](docs/consumer-guide.md#ownership-and-shutdown).
- **BREAKING:** `PlaybackRecoveryReason` gains `LIVE_BUFFER_EXHAUSTED`,
  `LIVE_BUFFER_EXHAUSTED_RETIREMENT_FAILED` and
  `LIVE_BUFFER_EXHAUSTED_PLAYER_CLEANUP_FAILED`; exhaustive `when` expressions need new
  branches. When a live target without server timeshift fills its bounded sample
  queues (for example while paused), `TvheadendPlaybackCoordinator` stops the
  Player, removes only its item, retires the target and reports
  `LIVE_BUFFER_EXHAUSTED` instead of failing playback with a player error; call
  `setLiveTarget()` again to resume at live.
  `LIVE_BUFFER_EXHAUSTED_RETIREMENT_FAILED` reports that retirement could not
  start, so the target may still be active and `stop()` or `setLiveTarget()`
  retries it. `LIVE_BUFFER_EXHAUSTED_PLAYER_CLEANUP_FAILED` reports a retired
  target whose Player stop or item removal could not be confirmed; the SDK
  keeps that cleanup pending (see `playerCleanupPending`). If the application has
  already left the item, the period fails and the subscription closes without a
  recovery reason. See
  [pausing live TV without timeshift](docs/consumer-guide.md#pausing-live-tv-without-timeshift).
- The `sdk-core` runtime dependency `at.bernhardberger.tvheadend:htsp` moves
  from 0.10.0 to the 1.0.0-beta.1 pre-release, and the HTSP gateway targets its
  1.0 API. HTSP types never appear in SDK signatures, but applications that also
  use the HTSP library directly resolve 1.0.0-beta.1. Servers
  older than HTSP 36 fail with the new `SessionFailure.ServerUpgradeRequired`
  (explicit retry) instead of an incompatible-server failure.
- **BREAKING:** `ServerCapabilities.apiVersion`, `allLimit`, `dvrLimit`,
  `streamingLimit` and `uiLevel`, and the matching `ServerCapabilities.create()`
  parameters, change from `Int?` to `Long?`, so unsigned 32-bit server values keep
  their full range.
- **BREAKING:** replies the SDK or the HTSP library cannot interpret are reported
  separately from server refusals. New members: `SessionOperationFailure`,
  `ArtworkFailure`, `RecordingFileFailure` and `SubscriptionOperationFailure`
  gain `MALFORMED_RESPONSE`; `DvrMutationResult`, `DvrProgressResult`,
  `DvrCutpointsResult`, `EpgSearchResult`, `StreamProfilesResult` and
  `SubscriptionOperationResult` gain `MalformedResponse`; `TimeshiftCommandResult`
  gains `MALFORMED_RESPONSE`. The server may have applied the command, so these
  are never retried automatically; see
  [malformed responses](docs/consumer-guide.md#malformed-responses). Exhaustive
  `when` expressions need new branches.
- **BREAKING:** `SessionFailure` gains `MetadataOverflow` and
  `ServerUpgradeRequired`. A metadata overflow retires the connection and
  reconnects for a full resynchronization with growing backoff, also after the
  session was ready.
- **BREAKING:** `SubscriptionTermination` gains `SUBSCRIBE_REJECTED`. When the
  server refuses a subscribe, the terminal state carries the typed refusal
  (for example `CONNECTION_LIMIT` or `ACCESS_DENIED`) whichever of the refusal
  and the stream end arrives first; a refusal without a typed outcome ends as
  `SERVER_REJECTED` after the restart grace period.
- **BREAKING:** DVR requests validate what the server requires: scheduling by
  explicit time needs a non-empty title (whitespace is permitted), and
  time-based rule start and stop minutes must be in `0..1439`. The
  automatic-rule `1440` sentinel is unchanged.
- A live subscription whose consumer falls behind its bounded event buffer now
  ends with `SubscriptionTermination.PUBLICATION_FAILURE`. Before, the HTSP 0.10
  transport suspended the connection reader until the consumer caught up,
  stalling every other subscription and metadata update on that connection.
  Session disconnect and shutdown always close the connection, including after
  a failed reconnect.

### Fixed

- DVR mutations are no longer confirmed by stats-only progress updates.
  `stopEntry` and `cancelEntry` now confirm only on a full update showing the
  entry completed or missed (or, for cancel, deleted). Updates that still show
  it scheduled or recording do not confirm, so the call returns
  `AcceptedButUnconfirmed` if no such update arrives before the confirmation
  deadline.
- `deleteEntry` on a completed recording now returns `Confirmed` when the server
  keeps the entry in its log after deleting the file and a full update reports
  the file missing; before, it returned `AcceptedButUnconfirmed` after the
  confirmation deadline. A confirmed delete therefore does not guarantee removal:
  the entry may stay in `dvrState` as `FILE_MISSING` until the server's log
  retention removes it.
- `TvheadendConnectivity` tracks only the current default network. After the
  default network switched, losing the new default kept the status `AVAILABLE`,
  so `UNAVAILABLE` was never reported and session retries on network return
  never fired.
- A credential DataStore file that cannot be parsed is now replaced on first
  access and reads as `Missing`, so it can be cleared and set up again. Before,
  every profile store operation returned `Unavailable` permanently. The consumer
  guide explains how to exclude the store from Android backups and device
  transfer.
- The metadata cache flushes the outgoing profile's pending EPG snapshot on
  profile switch, disconnect and shutdown; up to the last EPG write interval
  (60 s) of guide data was lost before. Catalog changes are written immediately
  instead of waiting behind a pending EPG write. Clearing the cache no longer
  follows symbolic links.
- The adapted Media3 H.264 reader keeps an IDR picture whose access unit
  delimiter and parameter sets arrive in a packet without a timestamp; the
  picture takes the timestamp of its first slice instead of being dropped.
  Multi-slice MVC/SVC pictures are no longer split at type-14 prefix NAL units.
- `sdk-testing` fakes no longer accept what production rejects:
  `ScriptedSubscriptionConnection` ignores scripting after unsubscribe or
  generation loss; after `loseGeneration()` commands return
  `TransportUnavailable` without recording values, late collectors receive only
  `Terminated(GENERATION_LOST)` without consuming their id, and `commitIfLive`
  returns null, matching the HTSP 1.0 transport contract; generation loss and
  broadcasts reach every collector even when one completes inline.
  `FakeTvheadendSession` binds scripted EPG coverage to the admitting proof and
  the requested channel order, and channels without coverage settle as
  `Ineligible` (single) or `TargetAbsent` (batch). Cancelled
  `FakeServerProfileStore` calls change nothing. The consumer guide and KDoc
  state exactly what `FakeTvheadendSession` enforces.
- A paused timeshift seek no longer blocks stop, target changes, shutdown or
  other coordinator commands while it refills the player. If a server stop
  interrupts the subscription during buffering, server delivery is paused again
  once that subscription restarts instead of playback being retired, and
  restarted periods keep the acknowledged pause. A seek or return-to-live that a
  later command interrupts while refilling a paused player still reports the
  server's acceptance.
- Closing a live subscription can no longer hang or end with the wrong reason.
  A close ends `Closed` even while a restart deadline is pending, and an ordered
  server termination keeps its reason across restart and seek-acknowledgement
  deadlines. A pending seek completes with `SubscriptionEnded` and packets
  drained during close are discarded. Teardown waits up to 5 s for running
  consumer code, then cancels it and returns the new
  `SubscriptionCloseResult.CONSUMER_DRAIN_TIMED_OUT` (or
  `CLEANUP_FAILED_AND_CONSUMER_DRAIN_TIMED_OUT` when cleanup also failed) instead
  of waiting indefinitely; exhaustive `when` expressions over
  `SubscriptionCloseResult` need new branches. A nested `close()`, `seek()` or `closeAndJoin()` from consumer
  delivery throws `IllegalStateException` instead of deadlocking. Stop and
  restart events during the closing drain are fenced, and a stream restarted
  before the subscribe acknowledgement no longer times out.
- Live subscription diagnostics saturate drop counts at `Long.MAX_VALUE` and
  withhold adapter labels that contain a device path or URI.
- The live sample-queue overflow error now reads "Live sample buffer limit
  reached" instead of a generic preparation failure. An overflow while replaying
  packets buffered before the Media3 period existed now closes the subscription;
  before, the period failed while the server kept streaming.
- A throwing Player operation or looper dispatch in `TvheadendPlaybackCoordinator`
  now completes that command as player-unavailable instead of escaping the
  coordinator, and a command that already ran keeps its real result.
- A malformed stream index in a live subscription now ends it as a malformed
  message instead of an infrastructure failure.

## [0.23.0]

Released 2026-10-04.

### Added

- `DvrRepository.changes: Flow<DvrChange>` provides best-effort, generation-stamped
  DVR lifecycle changes after initial sync, with independent local-command origin
  correlation. `DvrChange`, `DvrChangeKind`, `DvrChangeOrigin`, `DvrMutationKind`
  and `FakeDvrRepository.emitChange` support consumers and their fakes.
- Distinct aborted/stopped/completed change classification: the server's user-abort
  marker is not a failure notice; local stop intent or clearly early successful
  completion (more than 60 seconds early) identifies stops. Snapshot entry states
  remain unchanged. Failure changes are emitted at most once per entry per generation;
  file loss after completion is not a recording failure. Edits cannot claim lifecycle
  changes, STOP takes precedence, and schedule/delete attribution spans their related
  lifecycle events.

### Changed

- The new abstract repository member requires custom implementations to add
  `changes` and consumers to recompile: a minor pre-1.0 compatibility break.

## [0.22.0]

Released 2026-09-27.

### Added

- `RecordingPlaybackAdmission.GrowingStartOverOnly.resumePosition` carries the
  positive saved server position of an active recording when progress is
  supported. The historical class name is kept.

### Changed

- `RecordingPlaybackStart.RESUME` on an active single-file `.ts` recording with
  supported progress and a positive saved server position no longer returns
  `GROWING_RECORDING_RESUME_UNSUPPORTED`. Playback starts at 0:00 and seeks once
  to the saved position when the growing timeline becomes seekable, or 3 s
  before the recorded extent when the saved position is within 3 s of or past
  it. Without a seekable timeline within 20 s the resume is abandoned and
  playback simply continues. A viewer seek, target replacement, stop or close
  before the resume seek cancels it. A missing or zero saved position plays from
  the beginning, like `START_OVER`. The other active-recording gates are
  unchanged: unknown progress `NOT_READY`, unsupported progress
  `RECORDING_PROGRESS_UNSUPPORTED`, not exactly one usable file
  `TARGET_UNAVAILABLE`, non-TS `GROWING_RECORDING_DEFERRED`. The constant stays
  public but is no longer produced.
- While the progress hold of a pending completed or growing resume with a
  positive saved position remains active, checkpoints, pause and stop no longer
  report a position earlier than the saved one. The hold ends when the resume is
  applied (its seek is issued), cancelled, or abandoned: a growing resume is
  abandoned when no seekable timeline appears within 20 s, and a completed
  resume, as in 0.21, when the saved position is at or past the known duration.
  For a completed recording the hold also ends after a 60 s reporting-only
  backstop; its seek itself stays pending until the timeline becomes seekable. A
  pause or stop before a completed-recording resume seek previously overwrote
  the saved position with about 0.

## [0.21.0]

Released 2026-09-26.

### Added

- `MediaItem.isTvheadendLive()` reports whether a Media3 item carries the media
  id the SDK reserves for its live source, which the live source sets on its
  items, including during timeshift. SDK recording items, including growing
  recordings, never carry it, so consumers need not match media ids or URIs.
  Apps must not use that id for their own items.

### Changed

- `LivePlaybackObservation.Active.serverStopped` is true from a server stop of
  the live stream, with or without a `subscriptionIssue`, until the stream
  starts again, the subscription ends or the target is retired. A stop without
  an issue was previously indistinguishable from a healthy subscription. `Active`
  gains the property and `component4()`, and its `equals`, `hashCode` and
  `toString` now include it.

## [0.20.0]

### Added

- Live playback exposes TVHeadend text subtitle streams, the subtitle pages
  TVHeadend extracts from teletext, as selectable Media3 text tracks with the
  stream language. Each page replaces the previous one, keeps its line breaks
  and TVHeadend's colours without spaces at row edges, and a blank page clears
  it. Pages sent before the first audio or video sample are skipped, and a
  subscription with no other supported stream still fails as unsupported.
  Recordings are not covered, and raw teletext streams remain unsupported.

## [0.19.0]

### Added

- `channelComparator()` and `channelComparator(collator)` return the standard
  TVHeadend channel order: numbered channels by major number, minor number
  (absent first) and name; then unnumbered channels, including TVHeadend's
  number `0`, by collator-ordered name; missing or blank names last; `ChannelId`
  as the final tie-break. `Channel.hasChannelNumber` reports whether a channel
  has a user-visible number greater than zero.
- `TvheadendPlaybackCoordinator.setLivePriority(LiveSubscriptionPriority)` asks
  TVHeadend to let the current live subscription yield its tuner (`YIELD`, server
  weight 10) or restores the stream profile's default weight (`NORMAL`). The
  priority sticks to the current live target, including subscriptions the SDK
  re-opens for it, and resets to `NORMAL` for a new target or after `stop()`.
  Recordings and idle coordinators return `UNAVAILABLE`; servers without
  `subscriptionChangeWeight` return `NOT_SUPPORTED`. Stream profiles with
  TVHeadend's "force priority" option accept the request but ignore it. When to
  yield stays an application decision.
- Subscription infrastructure: `SubscriptionOptions.priority`,
  `SubscriptionConnection.changePriority` and `ActiveSubscription.setPriority`;
  `ScriptedSubscriptionConnection` records `requestedPriority` and
  `priorityChanges` and can `scriptPriority` results. The default
  `SubscriptionConnection.subscribe(id, channelId, options)` ignores the priority,
  so adapters that support it must override that overload.

### Changed

- `SubscriptionOptions` gained a defaulted `priority` constructor parameter. This
  is source compatible but a binary break: recompile subscription infrastructure
  adapters against this release.

### Fixed

- Live AC-3/E-AC-3, MPEG audio and AAC tracks carry the Media3 role flags for
  TVHeadend's ISO 639 `audio_type`, matching the recording path: `2` maps to
  `C.ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY` and `3` to
  `C.ROLE_FLAG_DESCRIBES_VIDEO`. Other, missing or out-of-range values keep no
  role flags.
- When TVHeadend stops a live subscription, for example because another
  subscription took its tuner, `subscriptionIssue` and the live observation now
  report the stop reason, and later status updates, until the stream starts
  again or ends, and the final subscription issue returned by `stop()` can now
  be that stop reason. Previously the issue was cleared while the stream was
  stopped.

## [0.18.0]

### Changed

- `PlaybackStopResult.PlayerUnavailable` is a data class carrying
  `finalSubscriptionIssue`. This is a source and binary break: replace
  `PlaybackStopResult.PlayerUnavailable` value matches with
  `is PlaybackStopResult.PlayerUnavailable`, read `finalSubscriptionIssue`, and
  recompile consumers against 0.18.0.

### Fixed

- `stop()` no longer discards the retired live target's final `SubscriptionIssue`
  when the target is retired but player cleanup fails. `PlayerUnavailable` is
  still reported in preference to `Stopped`; its issue is `null` when no live
  target was retired.

## [0.17.0]

### Added

- `ArtworkId.parse` resolves TVHeadend `imagecache/<id>` selectors, such as
  programme or recording images, to typed artwork IDs. External URLs and
  malformed selectors return `null`.
- `ContentGenre` decodes the raw ETSI EN 300 468 `contentType` into a
  `ContentCategory` and an optional `ContentSubgenre` covering every Table 28
  level-two value. `EpgEvent.contentGenre` and `DvrEntry.contentGenre` expose it;
  the raw `contentType` is unchanged.
- `DvrCutpoint.sceneBoundary` returns the end of a scene marker, where TVHeadend
  places the boundary.
- `DEFAULT_HTSP_PORT` names the default HTSP port used by server profiles.
- `createTvheadendLoadControl` provides the recommended Media3 buffer durations
  for live subscriptions and recordings, tuned with `PlaybackRecoveryPolicy`
  defaults.

### Changed

- `Channel.icon` and `ChannelTag.icon` are `ArtworkId?`. External icon URLs and
  malformed selectors are no longer exposed. The catalog cache format is
  unchanged.
- `TvheadendArtwork.create` accepts an `ArtworkId` and no longer returns `null`;
  the string overload is removed.
- `PlaybackStopResult` is a sealed interface. `Stopped` carries the retired live
  target's final `SubscriptionIssue`, so consumers no longer need to read
  `subscriptionIssue` before stopping. Replace enum matches and recompile
  consumers against 0.17.0.

## [0.16.0]

### Fixed

- Restore audio when live playback becomes ready or idle after buffering recovery.
  Keep recovery bounded to one audio-disable attempt per target, including paused
  playback, synchronous callbacks and stale timers.
- Avoid stale direct AC-3 playback-head positions after repeated timeshift seeks
  on TCL G10 / G10_4K_GB with Android 12. Use a fresh audio session for each direct
  AC-3 output on that platform; PCM, other devices, offload and tunneling retain
  their existing behavior.

### Added

- `TvheadendAudioOutputProvider` exposes optional passthrough, asynchronous startup
  configuration and bounded in-place audio-mode changes. Switching resets only
  audio renderers, preserving the source, live subscription and play intent.
  Audio overrides are cleared before re-enabling; consumers may restore remembered
  choices after fresh track support is reported. Cancellation and timeout do not
  allow a late mode change.

### Changed

- `createTvheadendRenderersFactory` accepts a defaulted audio-output provider.
  Its JVM signature changes; recompile consumers against 0.16.0. Media3 1.11.0,
  HTSP 0.10.0 and native decoder payloads are unchanged.

## [0.15.2]

### Fixed

- Retain already-received expired programmes for rewind labels and guide history
  after server deletion, using generation-scoped server time and valid channel
  successor evidence. Current and future correction deletions remain removals.
- Bound display-only history to six hours and a separate configured event-count
  limit; invalidate corrected, replaced, deleted-channel and previous-generation
  history without restoring live schedule or recording authority.

### Added

- `EpgSnapshot.historicalEvents` exposes immutable display history.
  `SessionObservation.eventAt` searches live schedule first, then history, using
  closed-start/open-stop boundaries. Guide consumers can combine `events` and
  `historicalEvents`. Cold starts and reconnects may have missing past metadata;
  history does not provide stream-to-UTC mapping or prove seekable buffer content.
- `EpgSnapshot.create` has a new defaulted history parameter. Its JVM signature
  changes; recompile consumers against 0.15.2.

## [0.15.1]

### Fixed

- Reuse unchanged public EPG events across snapshots to reduce whole-guide
  allocation during metadata updates, preserving immutable retained snapshots.
- Prepare EPG lookup indexes before publishing observations and reuse equal
  snapshots rather than rebuilding their indexes.
- Skip redundant persistent-cache restoration when reconnecting with retained
  metadata. Stream EPG cache records to avoid whole-guide serialization buffers.
  The previous internal EPG cache format is discarded and rebuilt automatically.
- Preserve public APIs, event limits and configured Guide coverage. These changes
  reduce avoidable allocation; they do not impose a new process-heap budget.

## [0.15.0]

### Changed

- `TvheadendTestResultFactory.boundCompletedRecordingPlayback` accepts a suspending
  `cutpoints` lambda for fake-backed recording-marker tests. The binding retains
  cancellation, generation and recording-identity checks around the response and
  rejects scripts that counterfeit observation expiry. The omitted-lambda response
  remains `NotReady`.
- The factory's JVM signature changes; recompile consumers against 0.15.0.
  See [recording cutpoint fixtures](docs/recording-cutpoint-fixtures.md).

## [0.14.0]

Track the full verified growing-recording seek extent using Media3's maintained
TS binary seeker. Retain one continuity-bound probe reader, cache the starting
PCR, and skip tail reads when size is unchanged. Confirm size and finality
together before retiring the probe so completion cannot freeze an earlier,
shorter extent. Playback seeks remain limited to verified media bounds.

Add abstract `isFinal`, `refreshSize` and `seek` members to
`GrowingRecordingFileReader` and expose `RecordingFileContinuity.isFinal`.
These are pre-1.0 breaking API additions: recompile consumers and update reader
fakes. Dependencies and native decoder payloads are unchanged.

Focused JVM regressions and actual Media3 MPEG2/H264 source checks cover growing
extent, paused/playing growth, seeks, retry and completion. Existing real Player
staged integration evidence applies to this source. Pathological regressing tail
PCR may retain an estimated extent and polling; explicit size refresh does not
share the read-path throttle. These remain nonblocking review observations.

## [0.13.1]

Current metadata observations and EPG coverage requests use the existing atomic
observation store without acquiring the EPG reducer's maintenance monitor. A
volatile generation fence preserves reset and replacement authority. Reducer
mutation and publication remain serialized, and retained snapshots stay immutable.

A representative 100-channel, 20,000-event JVM workload and paused-publication
regressions cover reader progress, live updates, retention, generation replacement,
batch ordering and cancellation. See [measurement and scope](docs/metadata-reader-contention.md).
Public signatures and dependency versions are unchanged.

## [0.13.0]

Add SDK-owned timeshift selections with clamped displacement feedback, moving
edge tracking, immediate direction reversal and same-segment commit validation.
The client-position-relative `seekTimeshiftBy` replaces the old server-reader
relative overload. Exact-target seeking and return-to-live remain available.
Consumers must recompile and use `playbackPaused` for presentation intent;
`serverPaused` describes transport delivery, which can temporarily run during
a paused seek.

Paused seeks obtain bounded normal-stream input while presentation stays paused,
wait for correlated readiness and video-buffer release, and restore transport
pause. Outcomes retain seek acknowledgement, buffering and pause-restoration
results separately. Failed pause restoration retires the owned target. Recovery
timers no longer treat deliberate pause as a playing-stream stall.

Preserve H.264 access-unit recovery prefixes with a version-pinned adaptation of
Media3's reader, retaining its slice parser and Apache attribution. Preserve
wall-clock mapping across repeated observations of the same buffer edge. Add a
public media-producing fake binding for offline consumer integration tests.

JVM and real ExoPlayer regressions cover repeated playing and paused seeks,
reference continuation, cancellation, replacement and boundary selection.
Consumer physical checks confirmed paused-picture updates on tested SD and HD
streams. These checks are not universal stream or device guarantees. HTSP 0.10.0,
Media3 1.11.0 and native decoder binaries remain unchanged.

## [0.12.1]

The SDK renderer factory uses Media3's maintained nonterminal decoder lifecycle
to drain a finite paused post-seek H.264 IDR sample. The period retains and
replays the actual sample once for fresh-decoder reference continuation. Audio
readiness is unchanged; no Player or server resume is requested by the repair.
Non-IDR input stays on the existing path. Real millisecond Player snapshots now
map within their observed timestamp bucket, without inventing a landing target.

Offline evidence covers a captured full-frame IDR, surface delivery, resumed
dependent video, delayed audio, second seek, replacement and release on the
standard emulator. It is not G10/server acceptance or universal H.264 field-picture
support. Public signatures, HTSP 0.10.0, Media3 1.11.0 and native payloads are
unchanged. See [implementation and evidence](docs/paused-frame-recovery.md).

## [0.12.0]

Accepted content seeks now discard old Media3 queues and deliver a playback
discontinuity only after usable new selected A/V samples arrive. Paused playback
can render a new first frame without an unsolicited Player or server resume.
No new media means no optimistic settlement or decoded-frame claim.

Accepted `TimeshiftContentSeekResult.Completed.seek` and sampled
`TimeshiftPlaybackPosition.Estimate.seek` provide opaque command correlation,
including when `readerReached` is unknown. Match non-null token identity rather
than a timer or reader coordinate. Recompile consumers for the updated
`TimeshiftTestFixture.playbackPosition` signature. See
[settlement semantics and offline evidence](docs/content-seek-settlement.md).
HTSP 0.10.0, Media3 1.11.0 and bundled FFmpeg/native payloads are unchanged.

## [0.11.0]

Propagate HTSP queue data errors through `SubscriptionEvent.Queue.errorCount`
and `LiveQueueDiagnostics.errorCount`. The nullable `Long` preserves unknown
versus zero and the entire unsigned-u32 range 0..4294967295. This cumulative
server count is separate from B/P/I frame drops and client packet drops.
Rendering stays redacted and subscription lifecycle behavior is unchanged.

Resolve publicly verified HTSP 0.10.0. Recompile all SDK consumers together:
the SDK queue-event constructor and HTSP `HtspQueueStatusMessage` constructor
and generated `copy` JVM signatures change. Kotlin queue-event construction
may omit the new argument (unknown); Java construction supplies a nullable
`Long`. This known ABI break requires a minor 0.x release. Media3 and native
payloads are unchanged. See [queue errors](docs/queue-error-diagnostics.md).

## [0.10.1]

Live Media3 periods accept equal, non-identical track groups carried by retained
Media3 overrides across restart or retune. Selection validates an available
group containing the selected single track at index zero. Retained sample streams
must belong to the selected current queue; switching tracks or periods resets the
stream instead of reusing another queue. Public APIs, HTSP 0.9.0, Media3 1.11.0,
native binaries, cancellation, and segment/seek contracts are unchanged.

Owning JVM regressions cover current/equal-fresh/unavailable groups, invalid
indices, retention, switching, deselection, and interruption. Offline real
ExoPlayer tests carry a stored override across unchanged and changed restart
tracks and a new-channel retune without a Player error, in playing and paused states. These establish
the SDK selector path, not the Player application's route or physical audio.
See [selection contract and evidence](docs/media3-selection-contract.md).

## [0.10.0]

Live playback now surfaces state-only subscription termination to Media3 instead
of silently stalling. Transport cancellation settles an unresolved seek without
replaying uncertain packets, and content-seek cancellation reaches its caller
instead of becoming an ordinary unavailable result. Operation cancellation through
the playback coordinator retires that coordinator owner.

Failed target replacement restores the recording's captured position rather than
restarting its source at zero. If restoration also fails, final progress uses the
pre-replacement snapshot and does not mistake partially replaced player state for
a natural recording end.

Live preparation waits for one initialized stream in each advertised supported
audio/video category, then allows unresolved alternatives up to one second for
format discovery. It prepares immediately when all supported formats are ready.
Alternatives still unresolved at the deadline are omitted for that period, so a
silent secondary track cannot block preparation indefinitely. Output retirement
and playback-side queries share the reader lock.

EPG capacity rejection now settles waiting single and batch acquisition requests
without partial retention updates or a permanent capability-denial latch. Existing
cooldown, stale-query authority, observation expiry, and cancellation rules remain.

The SDK now resolves published HTSP 0.9.0, retaining stricter reply-sequence
validation and safer JSON diagnostics. Stop/start and repeated-start stream
sequences replace Media3 readers and track groups in a new period on the same
subscription and application-owned Player. Stop fences the old segment without
EOS or unsubscribe; missing restart metadata has a five-second deadline. An
unresolved seek intersecting restart fails closed because protocol skip
acknowledgements carry no segment correlation.

This minor release changes the provisional infrastructure API: `SubscriptionTracks`
has a public constructor for boundary fakes, `ActiveSubscription.seek` gains an
expected-tracks overload, and exhaustive seek outcomes gain `SegmentUnavailable`.
Infrastructure consumers must rebuild and handle that outcome. Timeshift content
targets are segment-scoped; consumers combining sampled content and history must
check `TimeshiftTimeline.describesSameSegment`, not just `describesSameSubscription`.
`TimeshiftTestFixture.restartSegment()` models replacement without changing
transport identity. See [repair behavior and evidence](docs/audit-defect-repairs.md)
and the [stream restart contract](docs/stream-restart-contract.md). Host regressions
cover restart ownership and stale-coordinate fences; real ExoPlayer restart tests
pass on the configured Android device with recorded MPEG-audio and AC-3 fixtures.
This verifies audio track reselection and play/pause intent, not live-server video
restart or interruption-free playback across all codecs.

## [0.9.1]

Persistent cache namespaces now encode field boundaries unambiguously and use a
new namespace version. Legacy metadata and artwork are not restored or migrated
because the old namespace could identify different profiles. Restore removes
recognized legacy SDK namespaces without touching current namespaces or unrelated
application data. Opaque `ArtworkLoader.cacheKey` values change once on upgrade;
application-owned artwork caches miss once and retain their own eviction policy.
Cancelling a clear no longer leaves a connected session without a metadata writer.

DVR snapshots are reused until accepted DVR metadata changes. Known-channel
pointer updates no longer reconstruct an unchanged EPG snapshot. Observation
event lookups use a lazy snapshot-owned ID index; Now/Next lookups inspect only
the channel's retained events. Empty-DVR programme lookups skip EPG work entirely.
Public signatures, duplicate-ID ambiguity, ordering, retention and generation
authority are unchanged. See [cache and metadata repair evidence](docs/cache-metadata-repairs.md)
for correctness coverage, structural cost evidence and remaining limitations.

## [0.9.0]

Timeshift timelines expose immutable schedule-grade `TimeshiftWallClockMapping`
`Estimate` or `Unavailable` state, replacing the unavailable-only enum. Estimates
associate observed live buffer ends with existing server-time observations and
monotonic elapsed time, without extra polling. Playback samples carry matching
history; retaining a mapping during preview keeps labels and media targets stable.
Reader pause preserves timing evidence. Invalid stream continuity disables new
estimates until replacement. These are approximations with no UTC error bound;
seek coordinates and history validation are unchanged. The public host fixture
can script an estimated live-edge time. See [programme-time estimates](docs/programme-time-estimates.md)
for the consumer route and limitations.
The infrastructure `SubscriptionEvent.Timeshift` constructor also changes its
binary signature; rebuild infrastructure consumers against 0.9.0.

## [0.8.0]

Sessions can persist catalog and guide metadata between processes. Passing a
`MetadataCachePolicy` to `createTvheadendSession(epgCoveragePolicy, cachePolicy)`
makes the SDK store the published `ChannelCatalog` and `EpgSnapshot` per server
namespace (a hash of host, port and username) under the policy root, and seed
them as `Stale` repository states before the next connection attempt, so a cold
start can browse channels before the HTSP initial sync completes. The catalog is
written on every change and the guide is coalesced to one write per minute plus
a flush on disconnect. Files older than `metadataRetention` or that fail to
decode are deleted. Nothing served from disk is ever published as `CURRENT`.
`TvheadendSession.cache` exposes `SessionCache` with `statistics` and `clear()`;
sessions created without a policy report empty statistics. `FakeTvheadendSession`
gains `FakeSessionCache`.

Artwork bytes now persist under the same namespace. Hits avoid HTSP file loads;
entries expire after `artworkRetention` (30 days by default), with root-wide LRU
eviction against `artworkMaxBytes` (64 MiB by default). Statistics include encoded
artwork bytes and entry count, and `clear()` deletes artwork in every namespace
without allowing an already-pending fetch to repopulate it. Corrupt files and
indexes are discarded. In `sdk-android`, Coil models capture the opaque
`ArtworkLoader.cacheKey` derived from namespace and image ID for consistent
memory identity across sessions. Restart persistence comes from the SDK byte
store, not Coil memory. Without a policy keys remain process-local.
Fetches reject retired observations; Coil memory hits can reuse decoded content.
Persistence belongs to the SDK, not Coil's disk cache.

## [0.7.0]

Guide traffic no longer starves the consuming process. Every HTSP EPG add,
update or delete used to rebuild the full `EpgSnapshot`, including a per-channel
coverage scan over all retained events, and publish a new `SessionObservation`
whose equality walked the whole event list again in every state flow and UI
comparison. Profiling a small TV showed most of the process busy in that path
while the player idled. The session now reduces a burst of guide updates and
publishes once per drained burst (at least every 512 events under a continuous
stream), the snapshot builds coverage in a single pass and is reused unchanged
until retained events actually change, and `EpgSnapshot` compares through a
content hash computed once on the producing thread. Structural equality is
unchanged: equal snapshots remain equal.

Live recovery no longer treats an ordinary tune as a stall. A target that has
not yet presented selected audio is still being tuned, descrambled and
delivered, so it now runs on a separate `preparationDurationMillis` budget
(20 seconds by default) instead of the six-second stuck-buffering budget. Since
`0.6.0` that shared budget escalated every tune slower than six seconds to
`AUDIO_RECOVERY_EXHAUSTED`, and each replacement target restarted the same
timer, so a slow server produced an unbounded retune loop. The short budget
still applies once a target has presented audio, and a new target resets that
observation.

`TimeshiftPlaybackPosition.Estimate` now carries the `timeline` observed in the
same sample as its content coordinate, and `TimeshiftTimeline` gains
`describesSameSubscription`. A consumer that samples a position and reads
history separately can be interrupted by subscription replacement between the
two reads; combining those coordinates reports a false distance behind live and
can authorise a seek on the successor derived from its predecessor. Equality
alone cannot separate that case from ordinary edge advancement.

Artwork loads now hold at most three HTSP file handles open per connection.
Each load keeps a file open across `fileOpen`, chunked `fileRead` and
`fileClose` round trips; an unbounded burst, such as a channel list scrolling
picons, exhausted the server's per-connection file budget and turned every
further load into a rejection. Additional loads wait for a free handle instead.

`LiveSubscriptionDiagnostics` gains `clientDroppedPacketCount`, the number of
packets the client's own protocol queue evicted because the consumer fell behind.
Server-side frame drops were already reported in `queue`; without the client
count a consumer could not tell a starved server from a slow client.

The added `PlaybackRecoveryPolicy`, `Estimate` and `LiveSubscriptionDiagnostics` constructor fields are
intentional provisional ABI changes from `0.6.1`. HTSP remains pinned to
`0.7.0`; no protocol dependency substitution or new minimum TVHeadend version is
introduced.

## [0.6.1]

Add the SDK-owned `TimeshiftTestFixture` for application host tests of opaque
timeshift targets, displayed-position estimates and command results. Tests can
advance history, expire targets, replace subscriptions and hold dispatch replies
without reflection, real players or duplicated app timing policy. Production
constructors remain opaque; this is a backward-compatible consumer-testing fix.

## [0.6.0]

Live timeshift observations expose an absolute observed history and opaque
subscription-scoped content targets. A selection keeps its content coordinate
as the edge advances; execution rejects expired targets and subscription
replacement without clamping or sending the target to a successor.

The coordinator maps sampled Media3 position through bounded packet-timestamp
segments, preserving queued pre-seek content through pause and timestamp rebasing.
Mapping is explicitly estimated or unavailable, never inferred from server-reader
shift. Seek results carry a request-correlated reached reader coordinate when
available, which is not proof of displayed content. Programme wall-clock mapping
remains unavailable because the inspected packet/status path supplies no UTC anchor.
Capacity grants remain separate from observed seekable history.

The added packet-coordinate and timeshift-state constructor fields are intentional
provisional ABI changes from `0.5.0`. HTSP remains pinned to `0.7.0`; no protocol
dependency substitution or new minimum TVHeadend version is introduced.

## [0.5.0]

This provisional minor line exposes immutable, current-target live diagnostics
for source display metadata, frontend state and measurements, and server queue
depth, media span, and packet drops. Values are normalized, bounded, redacted,
generation-fenced, and cleared when the live subscription or coordinator target
ends. The exact no-input status maps to `NO_INPUT` unless a conflicting known
canonical error is present.

Playback target, timeshift command, and live-subscription issue outcomes now use
non-exhaustive SDK-owned singleton values instead of public enums. Stable
dispositions, non-exclusive categories, and direct predicates let Kotlin and
Java applications handle success, transient, terminal, unsupported,
configuration/access, and uncertain outcomes without becoming exhaustive over
future exact SDK values. Existing semantic distinctions, typed control flow,
cancellation propagation, and redacted fixed rendering are preserved.

Session observations now expose current-generation checks and cancellable waits,
and successful EPG search and stream-profile results retain their originating
session proof. Channel, EPG, and DVR snapshots expose explicit retained-metadata
authority plus display-safe retained data while a replacement generation is
synchronizing.

The profile-store contract now lives in `sdk-core`, with typed, redacted read and
mutation results implemented by the Android store and its JVM fake. Session and
operation failures expose a stable recovery disposition so applications can
distinguish automatic backoff, explicit retry, profile correction, and terminal
failures without interpreting transport details.

EPG coverage can be acquired for a batch of channel identifiers in one
generation-bound operation. Ordered per-channel settlements distinguish covered
channels from absent, rejected, or expired targets without turning partial
success into an aggregate exception.

The Media3 playback coordinator adds structured `launchIn` and `withLifetime`
entry points, a generation-fenced bind-and-install live workflow, and one
coherent live observation combining timeshift state, subscription issue, and
diagnostics. Applications still own the `Player` and all presentation and
service policy.

`sdk-testing` adds a composable generation-aware `FakeTvheadendSession` with
scriptable repositories, profile storage, artwork, playback bindings, typed
failures, and recorded calls for Kotlin and Java consumers.

The playback outcome replacement, profile-store move, and originating-session
result changes are intentional source and binary breaks from the provisional
`0.4.0` API; legacy enum machinery and compatibility shims are not retained.
The published set remains the same five SDK artifacts, and production dependency
identities are unchanged, including HTSP `0.7.0`. Compatibility remains
provisional during the major-zero line, and local or CI verification does not
establish Maven Central availability.

## [0.4.0]

This provisional feature release adds generation-bound EPG search with typed
requests and outcomes. Applications can constrain searches by channel, tag,
content type, language, and duration without receiving raw server errors, and
the captured current-session observation prevents a delayed result from being
accepted after its connection generation expires.

Applications can also configure the session's future EPG horizon and retained
event limit through `EpgCoveragePolicy`. Initial synchronization remains
bounded, while explicit coverage acquisition can extend the retained horizon
for the admitted generation.

The `sdk-core` ABI changes from `0.3.4` are additive: the policy, search request
and result types, repository search operation, and policy-taking session factory
overload are new, while existing declarations remain unchanged. The other JVM
module ABI dumps are unchanged. Compatibility remains provisional during the
major-zero line, and local or CI verification does not establish Maven Central
availability.

## [0.3.4]

This provisional patch release carries forward the timeshift speed-command fix
from the unpublished `0.3.3` development release. Pause and resume round trips
run on the subscription-owned dispatcher while preserving caller cancellation
and typed transport-failure outcomes.

Android settings screens can now use
`TvheadendServerProfileStore.loadProfileForEditing()` to distinguish missing,
unavailable, anonymous, and password profiles. Anonymous and password results
expose the normalized endpoint; only the password result exposes the exact
normalized username and password. These dedicated edit results have redacted
rendering and identity semantics rather than generated value, copy,
destructuring, or serialization APIs. Callers must keep immutable plaintext only
in private memory while an active secure edit surface needs it, then drop every
reference.

Connection reads remain opaque, password fields remain encrypted at rest with
endpoint-bound associated data, and storage, decryption, and cancellation still
fail closed. Existing APIs remain source- and binary-compatible, but compatibility
is provisional during the major-zero line. Local or CI verification does not
establish Maven Central availability.

## [0.3.3]

This provisional patch release fixes timeshift speed commands, including pause
and resume, when they are invoked from an application-owned coroutine context.
The subscription now runs the HTSP speed round trip on its owned dispatcher
rather than inheriting the caller's dispatcher.

Caller cancellation still propagates, and transport failures retain their
typed `TRANSPORT_UNAVAILABLE` outcome. Subscription admission, generation
fencing, and speed-result mapping are otherwise unchanged. The public API and
ABI are unchanged from `0.3.2`. Compatibility remains provisional during the
major-zero line, and local or CI verification does not establish Maven Central
availability.

## [0.3.2]

This provisional patch release fixes Media3 recovery for a live target that
remains buffering before any audio track is selected. The original 6,000 ms
initial recovery deadline now remains active while tracks are loading and emits
exactly one `AUDIO_RECOVERY_EXHAUSTED` request if the target is still buffering
without selected audio when that deadline expires.

If selected audio is present at the initial deadline, the existing two-stage
recovery still disables it and waits through a second 6,000 ms deadline before
escalating. Ready, idle, target replacement, and close continue to cancel
pending recovery, while stale timer callbacks remain fenced. The public API and
ABI are unchanged from `0.3.1`. Compatibility remains provisional during the
major-zero line, and local or CI verification does not establish Maven Central
availability.

## [0.3.1]

This provisional patch release fixes a Media3 coordinator completion defect.
When target installation already owns the application-owned `Player` looper,
the SDK now performs the guarded player operation inline instead of posting
back to the same looper. `TvheadendPlaybackCoordinator.setLiveTarget` can
therefore return its typed result after installing the source rather than
remain suspended while playback proceeds.

Queued operations retain their existing cancellation and rejected-post
behavior. The public API and ABI are unchanged from `0.3.0`. Compatibility
remains provisional during the major-zero line, and local or CI verification
does not establish Maven Central availability.

## [0.3.0]

This provisional minor release intentionally replaces the SDK's public session
and playback authority. `TvheadendSession.observation` now publishes lifecycle,
channel, EPG, DVR, capability, and freshness state together in one immutable
`SessionObservation`. Its `CurrentSessionObservation` is the generation-owned
capability for current operations, while retained stale data remains selectable
without becoming current again.

EPG acquisition, DVR mutations, recording progress and cutpoints, stream
profile discovery, and authenticated artwork now use the captured current
session. Delayed operations return typed expiration outcomes instead of
resolving colliding identifiers against a later connection generation.

Live and recording playback now start from an observation-bound
`PlaybackBinding`. The binding carries the exact generation and target identity
through queued coordinator work, delayed opens, extractor reopens, resume,
cutpoints, and progress reporting. Recording bindings also retain the DVR
incarnation, and completed recordings remain playable from the beginning when
progress support is unavailable.

Applications must migrate from the removed independent session flows, bare-ID
operations, and public raw playback routes to the aggregate observation and
binding-based API. This remains a major-zero release: source, binary, and
behavioral compatibility are provisional, and local or CI verification does
not establish Maven Central availability.

## [0.2.0]

This provisional feature release adds generation-bound discovery and selection
of TVHeadend stream profiles for live playback. Live targets can request a
server timeshift period, and the Media3 coordinator now exposes app-safe
timeshift state plus signed seek, bounded return-live, and server pause and
resume controls without taking ownership of ordinary player controls.

Live playback also reports the current target's canonical TVHeadend
subscription issue through a safe enum. Unknown or localized values map to
`UNKNOWN`; raw server text and stale target state are not exposed.

On Android, `TvheadendServerProfileStore` atomically persists one normalized
endpoint and anonymous or password authentication. Password fields are
encrypted with endpoint-bound associated data and are returned only through an
opaque connectable profile. The deprecated credential store remains binary
compatible over the same record.

This remains a major-zero release. Source, binary, and behavioral compatibility
are provisional, and local or CI verification does not establish Maven Central
availability.

## [0.1.2]

Initial provisional release of the independently maintained TVHeadend SDK. It
provides five focused libraries for HTSP-backed session and metadata workflows,
subscription and timeshift state machines, Android integration, Media3
playback, and reusable test fixtures.

The release includes generation-scoped lifecycle and cancellation, typed EPG
and DVR operations, authenticated artwork, bounded recording-progress policy,
and direct live and recording playback coordination. Growing pass-through
MPEG-TS playback is intentionally limited to stable recording identity and
validated MPEG-2, H.264, or HEVC seek paths; other growing containers and
unvalidated codecs fail closed.

The Media3 module includes the corresponding FFmpeg 6.0 source archive for its
optional GPL decoder fallback. The source, binary, and redistribution evidence
is recorded in
[`docs/ffmpeg-contingency.md`](https://github.com/bernhardberger/tvheadend-sdk/blob/v0.1.0/docs/ffmpeg-contingency.md).

This recovery aligns the tracked Maven OpenPGP public key and approved
fingerprint with the protected release environment. It does not change SDK
runtime behavior or API.

The signed `v0.1.0` and `v0.1.1` tags are retained as historical evidence after
their workflows stopped before signed-bundle retention or publication. No
`0.1.0` or `0.1.1` Maven artifacts or GitHub releases were created.

This is a major-zero baseline. It does not promise source, binary, or behavioral
compatibility or support. Publication and availability are independently
verified external state and are not established by this changelog entry.
