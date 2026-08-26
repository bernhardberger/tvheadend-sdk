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

## Tag Workflow

An annotated tag exactly matching the configured version starts one first-
attempt-only job. The job:

1. Requires the configured major-zero version, exact tag, checked-out commit,
   remote annotated-tag object, clean tree, and reachability from `origin/main`.
2. Builds, tests, and stages all five modules, then runs the real Android
   application consumer.
3. Records the SHA-256 identity of the exact 26 Maven originals and verifies
   sources, Javadocs, POM and Gradle metadata, standard legal entries, and the
   Media3 FFmpeg binary and corresponding-source payloads.
4. Requires all 104 signed Central member paths to be absent before reading the
   OpenPGP secrets, signs and verifies every original, and creates a deterministic
   Maven-layout ZIP plus release manifest and notes.
5. Retains those three public files as the seven-day
   `release-${{ github.run_id }}` workflow artifact before any Central mutation.
6. Revalidates the staged bytes, signatures, remote tag, and wholly absent
   Central paths, then performs one `publishingType=AUTOMATIC` upload request.

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
gh release create v0.1.1 \
  build/release/tvheadend-sdk-0.1.1-central.zip \
  build/release/release-manifest.json \
  --verify-tag --title v0.1.1 --prerelease \
  --notes-file build/release/release-notes.md
```

The owner then verifies the GitHub release target, prerelease state, two asset
digests, all five Central coordinates, and a clean application consumer that
resolves only published coordinates. These are explicit release-package actions,
not automatic recovery behavior in the repository tool.

If the upload result is ambiguous, Central is slow, the retained artifact is
missing, or any published byte differs, stop and investigate the recorded
deployment. Never recreate the tag or issue a blind second upload. A confirmed
post-upload GitHub failure is completed with the explicit `gh release create`
step after Central verification.

[`../release/openpgp/README.md`](../release/openpgp/README.md) defines the public
key and signature-verification contract.
