# Releasing

Releases remain provisional during the major-zero line and may contain
intentional source, binary, or behavioral changes. Release notes must state
those changes without promising compatibility or support. Major zero
communicates that status; the Maven version does not need an alpha suffix.

Local staging does not establish external publication or availability.
Publication and availability are independently verified external state. This
repository links a release only after that verification succeeds.

## One-time GitHub setup

The `central` GitHub Environment contains exactly these release secrets:

- `MAVEN_GPG_PRIVATE_KEY`: the ASCII-armored dedicated Maven secret-key export;
- `MAVEN_GPG_PASSPHRASE`: its passphrase, with no newline;
- `CENTRAL_PORTAL_TOKEN`: the pre-base64-encoded Central `username:password`
  token used as a Bearer value.

The Environment does not encode an approval gate. Only the conditional upload
step receives these values. The Central job has read-only repository and Actions
permissions; its history guard receives only the read-scoped built-in
`GH_TOKEN`, and its recovery path receives no credential. A separate dependent
job has repository-content write permission and receives only `GH_TOKEN` while
finalizing the GitHub release. It cannot access the `central` Environment.
Release secrets must never appear in source, command arguments, artifacts, logs,
reports, or generated output. The Maven key is separate from any Android APK
signing key.

Before any release tag is pushed, an active no-bypass tag ruleset must target
`refs/tags/v*` and restrict both updates and deletions. It must remain active and
unchanged until the workflow completes. GitHub and Central provide no cross-service atomic lock,
so this repository-administration control is the
immutability boundary between the final live-tag check and Central's upload.
The release owner verifies that rule alongside the Environment setup; the
workflow does not have enough repository-administration privilege to inspect a
complete bypass list.

Reviewed exact-tag GitHub Actions and repository `main` are the release trust
boundary. A malicious approved workflow, GitHub compromise, or repository-
administration compromise could use or exfiltrate the key and Central token.
That residual risk is accepted for this release path.

## Automatic tag sequence

Pushing an annotated exact tag for the configured release version starts the
workflow. The tag must resolve to the checked-out commit, and that commit must
already be reachable from `origin/main`:

1. Check out the complete tag history without persisting credentials.
2. Validate the Gradle wrapper and install the pinned JDK and Android SDK.
3. Run release-tool hostile self-tests and setup checks.
4. Build, test, and stage `build/local-maven` once, then verify those staged
   bytes and run the isolated staged consumer contract without rebuilding the
   SDK.
5. Query every retained GitHub Actions run and attempt for the exact tag. Every
   retained run must identify the same release commit; recreating the tag at a
   different commit fails closed. A new upload is authorized only when all 104
   deterministic Central member paths are absent and no earlier run or attempt
   started Central processing. Exact published originals select credential-free
   recovery; partial, mismatched, or sidecar-only state fails closed.
6. Re-read `origin` and require the remote tag to identify the exact local
   annotated tag object. Record one SHA-256 manifest for the exact 26 Maven originals
   across the five SDK modules.
7. Sign those originals at the release commit's timestamp with primary
   fingerprint `EAB02E488E7B944EAA6D65814BF0412FD2A3B741` and verify every
   signature with the tracked public key.
8. Create the mandatory MD5 and SHA-1 sidecars and a byte-deterministic exact
   104-member Maven-layout Central ZIP.
9. Re-read the exact remote annotated tag object at the upload boundary. Submit
   the ZIP once with `publishingType=AUTOMATIC`, wait for `PUBLISHED`, and
   resolve and compare all 104 published Central members.
10. Transfer the already-verified staged and release outputs through a one-day
    workflow artifact to the credential-isolated GitHub finalizer job.
11. Revalidate the two release assets, the Central ZIP and release manifest, and
    matching changelog notes before any GitHub release mutation.
12. Create or resume a draft GitHub prerelease, converge its two assets, verify
    their names, sizes, and SHA-256 digests, and only then publish it.

The GitHub prerelease marker does not change the Maven version or tag
vocabulary. The recurring path needs only the exact tag push. It has no
workstation, transfer-host, browser-upload, Portal-click, or separate approval
step.

## Failure and immutability

Any tag, version, coordinate, fingerprint, staging, signature, sidecar, ZIP,
Central state, resolved-byte, or release-note mismatch stops the workflow before
the GitHub release. A retained exact-tag run from another commit also stops both
upload and recovery. An observed live remote-tag change stops authorization,
Central processing, and GitHub finalization. Because no cross-service atomic
lock exists, changing or bypassing the mandatory tag ruleset during the final
Central request is a repository-administration compromise inside the explicitly
accepted trust boundary. The Central upload is not retried. If any retained exact-tag
workflow run or attempt started Central processing while the coordinates remain
absent, the history guard rejects a rerun. An ambiguous response requires
deployment-state investigation rather than another upload.

If all 26 Central originals already exist and match, a rerun skips upload,
downloads the exact 104 published members, and verifies and reuses those bytes.
Recovery is a separate step that receives no private key, passphrase, or Central
token. Missing Central members, partial presence, or any mismatch fails. A
GitHub failure leaves a draft; a rerun retains exact assets and completes missing
uploads before publishing.
Unexpected or duplicate asset names fail closed. Published release bytes are
immutable and must never be replaced.

The signed and checksummed Maven members remain in the Central ZIP and are not
duplicated as individual GitHub assets.

[`../release/openpgp/README.md`](../release/openpgp/README.md) defines the public
key and signature-verification contract.
