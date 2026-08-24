# Android lifecycle verification

Phase 5 Android acceptance was run on 2026-08-24 using a TCL Smart TV Pro on
Android API 31, firmware increment `AS50`.

The focused `AndroidLifecycleVerificationInstrumentationTest` ran as two exact
method invocations with an explicit package force-stop between them. The first
process consumed a mode-0600 app-private one-use profile and stored password
authentication through the production DataStore, Tink, and Android Keystore
path. The second process had a different process identifier, loaded the
authentication after a real disk and Keystore reopen, and authenticated an
SDK session to `Ready` against HTSP API 19. The profile and process marker were
consumed before connection, and the credential store reported `Missing` after
cleanup.

The second process also completed two independent default-network callback
lifecycles and observed `AVAILABLE` each time. For NSD, the separate
`sdk-media3` test package registered a reversible `_htsp._tcp` fixture under a
different Android UID on the same device. The production `TvheadendDiscovery`
path discovered and resolved that same-device platform service in two
independent collection lifecycles without an entered endpoint. The fixture then
unregistered successfully through its app-private stop marker.

A separate bounded platform probe found no `_htsp._tcp` advertisement from the
established TVHeadend instance, although the same device immediately discovered
and resolved generic DNS-SD services from the LAN. The fixture therefore proves
the SDK's Android NSD client path for the exact HTSP service type; interoperability
with a TVHeadend or Avahi advertisement remains unproven. The authenticated
real-server connection is a separate credential-lifecycle proof and is not
described as a discovered endpoint.

## Reproduction protocol

Ordinary connected-device test runs skip these tests unless their app-private
one-use markers are provisioned. Acceptance requires exact method invocations;
running either whole class does not reproduce the process boundary. An
assumption skip means the stage was not provisioned and is not an acceptance
pass.

1. Clean-install both instrumentation APKs. In the `sdk-media3` test package,
   create mode-0600 app-private `files/p5-4-start`, invoke
   `AndroidNsdAdvertisementFixtureInstrumentationTest#advertises_htsp_fixture_until_stopped`
   in the background, and wait for `p5_4_fixture=registered`.
2. Stream the approved profile over standard input into the `sdk-android` test
   package as mode-0600 `files/p5-4-real-server.json`. Invoke
   `AndroidLifecycleVerificationInstrumentationTest#stores_credentials_for_process_restart`
   and require `p5_4_stage=credentials-stored`.
3. Force-stop the `sdk-android` test package, then invoke
   `AndroidLifecycleVerificationInstrumentationTest#discovers_server_and_loads_credentials_after_process_restart`
   and require `p5_4_stage=android-lifecycle-passed`.
4. After stage two passes, create mode-0600 app-private `files/p5-4-stop` in the
   `sdk-media3` test package and wait for `p5_4_fixture=unregistered` plus the
   fixture test's successful completion.
5. Verify the profile, all three markers, and both package processes are absent,
   then uninstall both test packages.

Post-run checks found no one-use profile, process marker, fixture start marker,
or fixture stop marker, and both instrumentation packages were uninstalled. No
private endpoint, credential, real-server service identity, or private file
content is retained in this evidence.
