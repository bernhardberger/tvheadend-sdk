# Build matrix

The build intentionally uses one Kotlin line across JVM and Android modules.
Android modules use AGP's built-in Kotlin support and never apply
`org.jetbrains.kotlin.android`.

| Component | Pin |
|---|---:|
| Gradle wrapper | 9.7.0 binary distribution |
| Build JDK and Kotlin toolchain | 21 |
| JVM target and Java release | 17, class-file major 61 |
| Kotlin | 2.4.10 |
| Android Gradle Plugin | 9.3.1 |
| Android compile / target / minimum SDK | 36 / 36 / 24 |
| Android Build Tools | 36.0.0 |
| Coroutines | 1.10.2 |
| Coil core | 3.5.0 |
| AndroidX DataStore Preferences | 1.2.1 |
| Google Tink Android | 1.23.0 |
| HTSP | 0.7.0 |
| Media3 | 1.11.0 |
| JUnit Jupiter | 6.1.3 |
| AndroidX Test runner / ext JUnit | 1.7.0 / 1.3.0 |
| Turbine | 1.2.1 |
| detekt | 2.0.0-alpha.6 |
| Konsist | 0.17.3 |
| Dokka | 2.2.0 |
| Foojay resolver | 1.0.0 |

JVM modules and local Android unit tests run on JUnit Platform with JUnit
Jupiter. Android instrumentation tests use AndroidX Test's JUnit 4 runner. The
normal gate compiles instrumentation APKs but does not claim device execution.

`verifyBuildMatrix`, per-module `verifyClassMajor61`, and per-module
`verifyProductionDependencyGraph` tasks fail closed when the selected runtime
or production graph differs from this baseline. Kotlin ABI validation is
enabled independently in the three JVM modules.

## Android ABI limitation

KGP 2.4.10's ABI setup action is not run by AGP 9.3.1's built-in Kotlin path.
Enabling `abiValidation()` in an Android library therefore creates
`checkKotlinAbi` and `updateKotlinAbi` tasks whose required release-output
provider has no value. This was reproduced in both Android modules. Kotlin's
documented Maven-publication input is not an alternative because it does not
support Android AAR publications.

The Android modules retain strict explicit-API compilation, production class
version checks, source-boundary tests, and staged local publication. Do
not opt out of built-in Kotlin or wire AGP internal artifacts into KGP's
experimental ABI tasks merely to hide this incompatibility. Re-enable built-in
ABI validation for these modules when a future pinned AGP/KGP matrix supports
it. The three JVM modules remain ABI-dumped and checked on every gate.
