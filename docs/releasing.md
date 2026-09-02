# Releasing

Releases remain provisional during the major-zero line. Local staging is
verification, not publication, and published bytes are immutable.

## Protected Setup

The `central` GitHub Environment contains exactly:

- `MAVEN_GPG_PRIVATE_KEY`, the dedicated Maven secret-key export;
- `MAVEN_GPG_PASSPHRASE`, with no newline;
- `CENTRAL_PORTAL_TOKEN`, the base64 Central `username:password` token.

The tag workflow is read-only to the repository. The prepare step receives only
the OpenPGP key and passphrase; the upload step receives only the Central token.
No normal CI step, workflow artifact, command argument, or generated output may
contain a release credential.

Before pushing a release tag, the owner must verify an active no-bypass tag
ruleset for `refs/tags/v*` that restricts updates and deletions through release
completion. GitHub and Central provide no cross-service atomic lock, so this
administrative rule is the immutability boundary around the final remote-tag
checks.

## Push CI Staging

A successful first-attempt `main` push CI run retains the verified
`build/local-maven` repository, its exact 26-original manifest, and a provenance
record bound to the commit and CI run. Pull requests and reruns do not create
this seven-day `staged-publication-${{ github.run_id }}` artifact. The retained
bytes are produced only after the clean build, checks, staging verification,
real consumer contract, and release setup checks pass.

## Tag Workflow

An annotated tag exactly matching the configured version starts one first-
attempt-only job. The job:

1. Requires the configured major-zero version, exact tag, checked-out commit,
   remote annotated-tag object, clean tree, and reachability from `origin/main`.
2. Uses read-only Actions access to locate exactly one successful attempt-one
   `ci.yml` push run at the peeled tag commit and exactly its unexpired staging
   artifact.
3. Downloads that staging without setting up Java, Gradle, or Android, verifies
   the artifact's commit/run provenance, and locally rechecks its exact manifest,
   sources, Javadocs, POM and Gradle metadata, legal entries, and Media3 FFmpeg
   binary and corresponding-source payloads.
4. Requires all 104 signed Central member paths to be absent, using at most
   eight concurrent GETs, before reading the
   OpenPGP secrets, signs and verifies every original, and creates a deterministic
   Maven-layout ZIP plus release manifest and notes.
5. Retains those three public files as the seven-day
   `release-${{ github.run_id }}` workflow artifact before any Central mutation.
6. Revalidates the staged bytes, signatures, remote tag, and wholly absent
   Central paths with the same bounded GET checks, then performs one
   `publishingType=AUTOMATIC` upload request.

The upload step records `CENTRAL_DEPLOYMENT_ID` and stops. It does not poll,
retry, recover published members, or mutate GitHub Releases. A workflow rerun is
rejected because `github.run_attempt` is no longer one.

## Operator Completion

After Central reports the recorded deployment as `PUBLISHED`, the release owner
works from the exact tag and explicitly completes convergence:

```bash
./gradlew --no-daemon clean build check stageLocalPublication
./tools/check-staged-publication --write-manifest build/staged-publication.json
gh run download <run-id> --name release-<run-id> --dir build/release
./tools/publish-central-release --verify-published
gh release create v0.4.0 \
  build/release/tvheadend-sdk-0.4.0-central.zip \
  build/release/release-manifest.json \
  --verify-tag --title v0.4.0 --prerelease \
  --notes-file build/release/release-notes.md
```

The owner then verifies the GitHub release target, prerelease state, two asset
digests, all five Central coordinates, and a clean application consumer that
resolves only published coordinates. These are explicit release-package actions,
not automatic recovery behavior in the repository tool.

If CI provenance or run selection is ambiguous, the staging artifact expired or
is missing, the upload result is ambiguous, Central is slow, or any published
byte differs, stop. Never rebuild in the tag workflow, recreate the tag, or
issue a blind second upload. A confirmed post-upload GitHub failure is completed
with the explicit `gh release create` step after Central verification.

[`../release/openpgp/README.md`](../release/openpgp/README.md) defines the public
key and signature-verification contract.
