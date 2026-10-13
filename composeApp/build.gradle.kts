import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":fp-app-mobile-shared"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.androidx.activity.compose)
                // The androidx.compose BOM is pinned in gradle/libs.versions.toml
                // (2025.11.00 = the Jetpack Compose 1.9.4 wave CMP 1.9.3 builds
                // on); KGP 2.2 deprecated applying it via platform() inside
                // multiplatform source sets (KT-58759), and every compose
                // artifact here already arrives transitively via Compose
                // Multiplatform 1.9.3, so the pin stays catalog-only.
            }
        }
    }
}

android {
    namespace = "org.fauste.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.fauste.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// --- Rust ------------------------------------------------------------------

val cargoHome: String = System.getenv("CARGO_HOME")
    ?: "${System.getProperty("user.home")}/.cargo"

val androidHome: String = System.getenv("ANDROID_HOME")
    ?: "${System.getProperty("user.home")}/Android/Sdk"

// Resolved at configuration time so the task is configuration-cache friendly
// (no project/rootProject lookups inside task actions).
val rustRoot: File = rootProject.file(".")
val jniLibsDir: File = layout.projectDirectory.dir("src/main/jniLibs").asFile
val stagedRustLibs = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
)
val stagedSoFiles = stagedRustLibs.map { (abi, triple) ->
    File(jniLibsDir, "$abi/libfp_backends_mobile.so")
}

val buildRust by tasks.registering(Exec::class) {
    group = "rust"
    description =
        "Builds the fp-backends-mobile cdylib for arm64-v8a and x86_64 with " +
            "cargo-ndk and stages the shared libraries under src/main/jniLibs."

    workingDir(rustRoot)

    // Declared inputs/outputs so Gradle up-to-date checks skip the cargo
    // build when nothing in the Rust workspace changed.
    inputs.file(File(rustRoot, "Cargo.toml")).withPropertyName("workspace-manifest")
    inputs.file(File(rustRoot, "Cargo.lock"))
        .withPropertyName("workspace-lockfile")
        .optional(true)
    inputs.file(File(rustRoot, "crates-mobile/fp-backends-mobile/Cargo.toml"))
        .withPropertyName("crate-manifest")
    inputs.dir(File(rustRoot, "crates-mobile/fp-backends-mobile/src"))
        .withPropertyName("crate-sources")
    inputs.file(File(rustRoot, "crates-mobile/fp-backends-mobile/uniffi.toml"))
        .withPropertyName("uniffi-config")
    outputs.files(stagedSoFiles).withPropertyName("staged-libs")

    // cargo and cargo-ndk live in ~/.cargo/bin, which is not on the default
    // PATH of this machine. Gradle resolves the executable against the
    // daemon's PATH (not the environment set below), so the absolute path is
    // mandatory; the PATH override still lets cargo-ndk find `cargo` again.
    environment("PATH", "$cargoHome/bin:${System.getenv("PATH")}")
    // cargo-ndk locates the NDK under the Android SDK; make the default
    // explicit for environments where the shell has not exported it.
    environment("ANDROID_HOME", androidHome)
    // cargo-ndk auto-detects the NDK from $ANDROID_NDK_HOME first and falls
    // back to the newest NDK under $ANDROID_HOME/ndk. Only inject the pinned
    // default when no NDK is already selected, so an exported
    // ANDROID_NDK_HOME always wins.
    if (System.getenv("ANDROID_NDK_HOME") == null) {
        environment("ANDROID_NDK_HOME", "$androidHome/ndk/27.0.12077973")
    }
    commandLine(
        "$cargoHome/bin/cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        // Link against the API 26 sysroot: matches minSdk and provides the
        // libaaudio.so stub the AAudio backend links against.
        "-P", "26",
        "build", "--release",
        "-p", "fp-backends-mobile",
        "--features", "uniffi",
    )

    doLast {
        // cargo-ndk writes to target-triple directories, not target/release.
        stagedRustLibs.forEach { (abi, triple) ->
            val so = File(rustRoot, "target/$triple/release/libfp_backends_mobile.so")
            val abiDir = File(jniLibsDir, abi)
            abiDir.mkdirs()
            so.copyTo(abiDir.resolve(so.name), overwrite = true)
        }
    }
}

tasks.named("preBuild") { dependsOn(buildRust) }
