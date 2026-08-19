# Gradle wrapper provenance

P1-1 bootstrapped the Gradle 9.7.0 generated launchers and wrapper JAR from the
clean sibling HTSP 0.3.0 release checkout at commit
`58eea6e2374a4f9e607539bd746f5387472a7690`. The files were already validated
by that repository's release and CI gates; no project-specific build logic is
present in them.

The binary distribution is pinned in `gradle-wrapper.properties` with the
Gradle-published SHA-256
`84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae`.

The checked-in generated files have these SHA-256 values:

| File | SHA-256 |
|---|---|
| `gradlew` | `a5a5c199ba02189ae8c46a334223371a20599d9c298ef65e7540ede4a3f72d59` |
| `gradlew.bat` | `59328c7a17f673b1a63040bfb380a0c749e5d6df3406f7f18641060314cd9aa1` |
| `gradle/wrapper/gradle-wrapper.jar` | `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` |
