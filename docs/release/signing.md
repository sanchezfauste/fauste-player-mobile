# Release signing

How the release artifacts get signed, where the keystore lives, and what the
build reads. The one non-negotiable rule first:

> **No keystore is committed to this repository, and none ever will be.**
> Signing keys are created and kept outside the tree — on the maintainer's
> machine and in the CI secret store. The repository contains only the
> *plumbing*: the `signingConfigs` block in `composeApp/build.gradle.kts`
> that reads four properties, and this document.

## The property-driven build wiring

`composeApp/build.gradle.kts` wires the release signing from four Gradle
properties:

| Property | Meaning |
|---|---|
| `RELEASE_STORE_FILE` | Path to the keystore. Relative paths resolve against the repository root; absolute paths work as-is. |
| `RELEASE_STORE_PASSWORD` | The keystore's password. |
| `RELEASE_KEY_ALIAS` | The alias of the key to sign with. |
| `RELEASE_KEY_PASSWORD` | The key's own password. |

**All four or nothing.** If any one of the four is missing, the release build
type simply carries *no* signing config: `assembleRelease` and `bundleRelease`
still succeed and produce **unsigned** artifacts (`composeApp-release-unsigned.apk`
/ an unsigned AAB). That is the state CI builds in — a legal, verifiable build
that cannot be installed. When all four are present, the same tasks produce
signed artifacts.

Gradle reads properties from (in order): `-P` command-line flags, the
`GRADLE_USER_HOME` `gradle.properties` (`~/.gradle/gradle.properties`), and the
repository's own `gradle.properties`. **Do not put signing properties in the
repository's `gradle.properties` — it is committed.** The recommended locations
are the other two:

- **One-off / verification builds:** pass the properties inline:

  ```bash
  ./gradlew :composeApp:assembleRelease \
    -PRELEASE_STORE_FILE=/absolute/path/to/release.jks \
    -PRELEASE_STORE_PASSWORD=••• \
    -PRELEASE_KEY_ALIAS=my-key \
    -PRELEASE_KEY_PASSWORD=•••
  ```

- **The maintainer's machine:** put the four lines in `~/.gradle/gradle.properties`
  (the file usually already exists — it is also where `org.gradle.jvmargs` and
  JDK settings often live). It sits outside every repository you own, is read
  by every Gradle build, and is never seen by git.

## Creating a keystore

The maintainer's release keystore (do this once, back it up somewhere safe and
offline — losing it means you can never update the app under the same identity):

```bash
keytool -genkeypair -v \
  -keystore /path/outside/the/repo/fauste-release.jks \
  -alias fauste-release \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass 'choose-a-store-password' \
  -keypass 'choose-a-key-password' \
  -dname "CN=Fauste Player, O=Fauste, C=ES"
```

Notes from this task's verification runs:

- JDK 25's `keytool` writes a **PKCS12** store by default. PKCS12 keystores
  tolerate a key password different from the store password inconsistently
  across tooling — the first verification attempt with differing passwords
  failed at packaging with `KeytoolException: Given final block not properly
  padded`. Using the **same password for `-storepass` and `-keypass`** is the
  robust pattern; the two Gradle properties then carry the same value, and
  you keep the freedom to split them later if your toolchain proves it works.
- `keysize 4096` and `validity 10000` (days, ~27 years) follow the
  [Android app signing guidance](https://developer.android.com/studio/publish/app-signing);
  RSA ≥ 2048 is required for Play. Play Console requires an APK
  **v2+ signature** — AGP's release packaging signs with the v2 scheme at
  this app's minSdk 26 (v1 JAR signing is legacy and skipped; v3 key
  rotation is not enabled), and the verification run confirms it:
  `apksigner verify -v` → `Verifies`, `Verified using v2 scheme (APK
  Signature Scheme v2): true`.

### The TEST keystore used for release verification (never the real one)

The release artifact must be verified end-to-end — installed, launched,
driven — which requires a signature. Ruling: the verification build is signed
with a **locally generated TEST keystore** that exists only on the build
machine and is never committed. The exact command used:

```bash
keytool -genkeypair -v \
  -keystore /tmp/opencode/release-signing/fauste-release-test.jks \
  -alias fauste-test \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass fauste-test-store -keypass fauste-test-store \
  -dname "CN=Fauste Player TEST, OU=Release Verification Only, O=Fauste, C=ES"
```

The path is in the system temp directory (any git-ignored location works —
the repository's `.gitignore` already excludes `local.properties` and the
`build/` tree). The DN says what it is: **TEST**. A keystore like this can be
regenerated at will; the release signing it produces verifies the *build*,
never the *identity* — it must not be used to publish anything.

## The maintainer's Play Console step

The spec's DoD names a *signed AAB for Play Console* as the release step. With
the four properties in place:

```bash
./gradlew :composeApp:bundleRelease
```

produces `composeApp/build/outputs/bundle/release/composeApp-release.aab`,
signed with the real key, ready for Play Console's internal testing track.
Uploading that AAB and letting Play manage signing (enabling *Play App
Signing*) is the maintainer's workflow decision; either way, the key material
stays off the repository.

## How CI consumes the secrets

The three test lanes (CI, Android, Emulator) deliberately stay **unsigned**:
they prove buildability and behaviour, not identity — an unsigned artifact is
a fully meaningful CI output, and a fork or a leaked runner log exposes
nothing. Only the release lane consumes signing secrets.

`release-build.yml` reads the four repository secrets and follows the build's
own all-four-or-nothing contract:

| Secret | Maps to |
|---|---|
| `RELEASE_STOREFILE_B64` | base64 of the keystore — decoded onto the ephemeral runner and passed to the build as `RELEASE_STORE_FILE` |
| `RELEASE_KEYSTORE_PASSWORD` | `RELEASE_STORE_PASSWORD` |
| `RELEASE_KEY_ALIAS` | `RELEASE_KEY_ALIAS` |
| `RELEASE_KEY_PASSWORD` | `RELEASE_KEY_PASSWORD` |

With all four configured, each GitHub release carries the **signed** bundle
(`fauste-player-<version>-release.aab`) plus a **signed universal APK**
(`fauste-player-<version>-universal.apk`, extracted from that bundle with the
SHA-256-pinned bundletool), each with its checksum, and the APK's v2
signature is verified in-lane (`apksigner verify` — the "Before shipping"
check, automated). With any missing, the lane degrades to the unsigned
provenance bundle and the release note saying so — CI stays green without
secrets.

Adoption (once):

```bash
# 1. Create the real keystore — the "Creating a keystore" section above
#    (same store/key password, per the PKCS12 note).
# 2. Upload the four secrets; the values are read hidden, never on a
#    command line:
base64 -w0 /path/to/fauste-release.jks > /tmp/opencode/keystore.b64
gh secret set RELEASE_STOREFILE_B64 < /tmp/opencode/keystore.b64
gh secret set RELEASE_KEYSTORE_PASSWORD   # paste when prompted
gh secret set RELEASE_KEY_ALIAS
gh secret set RELEASE_KEY_PASSWORD
rm /tmp/opencode/keystore.b64
```

Security posture: only write-access paths can reach the signed build — the
release pull request merge on `main`, or a manual `workflow_dispatch`
(which requires write access to run). The keystore and the properties live
in the ephemeral runner's temp and home and die with it; nothing is
printed. A `workflow_dispatch` rebuild of an older release signs that
tag's tree but leaves any earlier unsigned assets attached — delete them
by hand (`gh release delete-asset`) if you want the release page clean.

## Verification trail

- Unsigned lane: `assembleRelease` + `bundleRelease` green with no properties
  (CI's shape).
- Signed lane: the same tasks with the test-keystore properties produce
  `composeApp-release.apk` + `composeApp-release.aab`;
  `apksigner verify -v` reports `Verifies` (v2 scheme), certificate DN
  `CN=Fauste Player TEST, OU=Release Verification Only, O=Fauste, C=ES`.
- The signed, R8-minified APK was installed and driven end-to-end on the
  API 34 emulator — see [`measurements.md`](measurements.md).
