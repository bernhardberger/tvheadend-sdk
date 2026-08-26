# OpenPGP Release Trust Model

This directory contains the public verification identity for Maven releases.
The dedicated Maven key is separate from every Android signing key.

- `public-key.asc` is one public-only, signing-capable, non-expiring primary key
  with no subkeys.
- `primary-fingerprint.txt` contains
  `EAB02E488E7B944EAA6D65814BF0412FD2A3B741` and one newline.
- The sole approved UID is
  `Bernhard Berger <bernhard.berger@gmail.com>`.

The protected prepare step imports the private primary into an ephemeral
`GNUPGHOME`, selects the full fingerprint, passes the passphrase through standard
input, signs each of the 26 originals, and verifies every detached signature
against a separate keyring loaded from the tracked public key. The retained
workflow artifact contains only public release material.

The later upload step receives no private key or passphrase. It re-verifies the
retained signatures with the public key and receives only the Central token for
the single upload request. There is no credential-bearing recovery path.

Never print a private key, passphrase, or Central token or place one in source,
arguments, artifacts, logs, reports, or generated output. A malicious approved
workflow or repository-administration compromise remains inside the explicitly
accepted release trust boundary.

The public key is published at `keyserver.ubuntu.com`, which Maven Central
supports. Publication is complete only after an explicit operator verifies the
Central bytes and creates and verifies the GitHub prerelease.
