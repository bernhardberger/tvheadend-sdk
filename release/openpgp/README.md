# OpenPGP release trust model

This directory contains the public verification identity for Maven releases.
The owner created a dedicated Maven/OpenPGP key. Any Android APK signing key is
a separate credential and must never be reused. The approved public UID is
exactly `Bernhard Berger <bernhard.berger@gmail.com>`. The key has no expiry,
and its primary key is signing-capable.

The directory contains two tracked regular files:

- `public-key.asc`: one ASCII-armored public key block with no secret primary
  or secret subkey packets;
- `primary-fingerprint.txt`: the full 40-hex uppercase primary fingerprint
  `EAB02E488E7B944EAA6D65814BF0412FD2A3B741`, followed by one newline.

Placeholders, short key IDs, subkey fingerprints, multiple primary keys,
ambiguous UIDs, invalid, disabled, revoked, expired, or expiring keys and UIDs,
and private packets are rejected. Release signatures must produce machine-
readable `VALIDSIG` evidence naming the tracked fingerprint as both signer and
primary fingerprint.

The setup and publish guards ask GnuPG to parse the tracked armor and require
exactly one signing-capable, non-expiring primary key, no subkeys or secret
packets, and exactly the approved valid UID and primary fingerprint.

Reviewed exact-tag GitHub Actions and repository `main` are the accepted release
trust boundary. The encrypted `MAVEN_GPG_PRIVATE_KEY` and
`MAVEN_GPG_PASSPHRASE` values live only in the `central` GitHub Environment and
are exposed only to the conditional upload step. For a new Central deployment,
the tool imports exactly one subkey-free private primary through subprocess
standard input into an ephemeral `GNUPGHOME`, selects the full primary
fingerprint, fixes the signature time to the release commit timestamp, and
passes the passphrase only through subprocess standard input. Recovery of an
already published release receives no private key, passphrase, or Central token.
Both paths verify signatures in a separate public-only temporary keyring loaded
from `public-key.asc`.

Never print the private key, passphrase, or Central token or place them in
source, process arguments, artifacts, logs, reports, or generated output. A
malicious approved release workflow, GitHub compromise, or repository-
administration compromise could use or exfiltrate the release credentials. The
owner accepts that residual risk for the automatic release path.

The public key is published at `keyserver.ubuntu.com`, which Maven Central
supports, and is retrievable by its full fingerprint. The tracked export matches
that public key. Key creation, public export, keyserver publication, and GitHub
Environment setup are one-time owner operations. Publication has not occurred
until the tagged workflow completes and every coordinate resolves with matching
bytes.
