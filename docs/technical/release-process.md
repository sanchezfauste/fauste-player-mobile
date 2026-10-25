# Release process

The release artifact is an Android app, so the release process is
Gradle-shaped: R8, signing, an AAB — plus the project's own
verification battery. The desktop's page
([release process](https://github.com/sanchezfauste/fauste-player/blob/master/docs/technical/release-process.md))
governs everything shared (Conventional Commits, SemVer) — including
the release-please automation, ported here with one adaptation: the
version lives in `version.txt`, not in Cargo metadata, because
release-please has no Gradle updater and `version-file` is its `simple`
strategy's native mechanism for a plain version file.

## Release automation (release-please)

Every push to `main` keeps a release pull request up to date
(`chore: release X.Y.Z`, from
[`release-please-config.json`](../../release-please-config.json)): the
CHANGELOG section regenerated from the Conventional Commits since the
last tag, and the bump in `version.txt`,
`.release-please-manifest.json`, `Cargo.toml` (the workspace version)
and `Cargo.lock` (the `fp-backends-mobile` entry). Merging that PR
tags `vX.Y.Z` and creates the GitHub release; because releases created
with `GITHUB_TOKEN` do not trigger other workflows, the **same run**
builds the tag's bundle and attaches it with its SHA-256 — signed, plus
a signed universal APK, when the signing secrets are configured, or
the honest unsigned provenance bundle with its note otherwise
([release-build.yml](../../.github/workflows/release-build.yml),
[signing.md](../release/signing.md)).

The app version's single source is `version.txt`;
`composeApp/build.gradle.kts` reads it at configuration time and
derives `versionCode = major×10000 + minor×100 + patch` (`0.1.0` =
`100`), so the code rises with the version without a second
hand-edited number. The release's artifacts follow the signing secrets
([signing.md](../release/signing.md)): configured, the release carries
the signed bundle plus a signed, directly-installable universal APK
whose v2 signature is verified in-lane; unconfigured, the unsigned
bundle is provenance only and the note says so — Play Console stays
the distribution channel of record either way. The release history
starts at the hand-tagged `v0.1.0` (the phase 0–5 state);
release-please does not look earlier than that tag.

Repository setup (one-time, already in place): "Allow GitHub Actions to
create and approve pull requests" (Settings → Actions → General), and
optionally the `RELEASE_PLEASE_TOKEN` secret (a fine-grained PAT with
contents and pull-requests write) so the release PR runs the CI lanes
— pull requests opened with the default `GITHUB_TOKEN` do not run
workflows.

## R8 and minification

The release build type enables `isMinifyEnabled` and
`isShrinkResources` with
`proguard-android-optimize.txt` plus `composeApp/proguard-rules.pro`.
Most of the classpath ships its own consumer rules or needs none
(media3-common 1.11.1 ships `-dontwarn` set; androidx and Compose ship
the baseline profile; Koin's graph is constructor lambdas, nothing
reflective; the app's own classes are kept by their manifest entry
points). Two dependencies do not, and the keep set is scoped to exactly
them:

| Rule | Why it is needed |
|---|---|
| `-keep class org.fauste.player.rust.** { *; }` | The UniFFI bindings. uniffi-bindgen 0.28 generates no consumer rules and prescribes none. `UniffiLib`'s methods are resolved **by name** through JNA's `Native.load` proxy (an R8 rename is an unsatisfiable symbol lookup at the first FFI call); `RustBuffer` extends `com.sun.jna.Structure`, whose fields JNA discovers reflectively **by name** (they are the native struct members); the callback interfaces are invoked reflectively. The file is regenerated code, so hand-tuning inside it is unmaintainable — it is kept wholesale |
| `-keep class com.sun.jna.** { *; }` + `-dontwarn com.sun.jna.**` | JNA (the `jna` AAR, 5.19.1) ships **no** consumer `proguard.txt` — verified by inspecting the archive (classes, `jni/*`, `res/*` only). Structure field discovery, library proxies and callback invocation all reflect into these classes; an obfuscated JNA fails at the first `Native.load` |

The resulting APK is proven, not assumed: the release smoke ran its
19-PASS set against the R8-minified artifact
([Testing](testing.md)).

## Signing

Property-driven and all-or-nothing: `composeApp/build.gradle.kts` reads
four Gradle properties (`RELEASE_STORE_FILE`,
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`); missing any one leaves the release build type
with **no** signing config, and `assembleRelease`/`bundleRelease` stay
green producing honest unsigned artifacts. CI builds unsigned on every
PR — it proves R8 and packaging, never identity — so R8 regressions
break CI instead of the maintainer's local signing step.

The full setup — keystore creation commands, the recommended property
locations (`~/.gradle/gradle.properties`, never the committed one), the
PKCS12 same-password note, the TEST-keystore pattern used for release
verification, and the CI-secrets shape (commented, not half-shipped) —
lives in [`docs/release/signing.md`](../release/signing.md).
**No keystore is committed, and none ever will be**: `.gitignore`
excludes `*.jks`/`*.keystore` as a structural guarantee.

## The AAB

`./gradlew :composeApp:bundleRelease` produces
`composeApp-release.aab` — signed when the four properties are present
— ready for Play Console's internal testing track. Enabling Play App
Signing is the maintainer's workflow decision; either way the key
material stays off the repository. Each GitHub release also carries the
CI-built unsigned twin of this bundle, for provenance (see
[Release automation](#release-automation-release-please)).

## Measurements

The spec's Phase-5 targets (cold start < 2 s, memory < 150 MB, CPU
idle < 2 %, 10 h screen-off playback) are **measured where the
emulator allows, with the methodology stated** and the
emulator-vs-hardware line drawn honestly:
[`docs/release/measurements.md`](../release/measurements.md). The
headlines from the 2026-10-21 runs against the minified, test-signed
release APK: cold start median **1.18 s** (logcat timeline, five COLD
iterations), peak PSS **62 MB** (`dumpsys meminfo`, three states), and
the design property the CPU section verifies — screen-off playback
cost collapses to ~⅓ of the screen-on figure when the screens'
lifecycle-aware collection freezes. **No battery number is quoted from
the emulator** — the virtual power source makes any figure a
fabrication; the document records the on-device methodology
(batterystats reset → timed screen-off window → drain attribution)
for the hardware the maintainer owns.

## Before shipping

- All lanes green: the cargo gates (both feature states), the Gradle
  battery, `scripts/emulator-test.sh`.
- The app smoke re-run if the service startup sequence or the FFI
  surface changed since the last run.
- `apksigner verify -v` on the signed artifact (v2 scheme, the
  Play-mandatory one at minSdk 26).
- The README's feature table and roadmap reflect what actually ships;
  recorded deferrals never read as shipped features.
