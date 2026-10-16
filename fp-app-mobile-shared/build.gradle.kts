import java.io.File
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain {
            // The UniFFI-generated Kotlin bindings from Task 4 are committed under
            // crates-mobile/fp-backends-mobile/bindings/kotlin (package
            // org.fauste.player.rust); Kotlin does not require the directory
            // layout to match the package name.
            kotlin.srcDir("../crates-mobile/fp-backends-mobile/bindings/kotlin")
            dependencies {
                // The generated bindings compile against JNA; they only load the
                // native library lazily (UniffiLib.INSTANCE), so no engine call
                // ever happens without the cdylib being present at runtime.
                // The @aar packaging (published on Maven Central since JNA
                // 5.10) is required: the plain jar ships no Android
                // libjnidispatch.so, so real FFI calls on a device need the
                // AAR's bundled natives.
                implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
                // api: composeApp consumes appModule() and Koin transitively.
                api(libs.koin.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.koin.android)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                // runTest / StandardTestDispatcher: the poll-loop cancellation
                // tests drive virtual time while the real engine calls run.
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

android {
    namespace = "org.fauste.player.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Unit tests must fail loudly when real android.jar methods are hit,
            // not silently return default values.
            isReturnDefaultValues = false
        }
    }
}

// Ruling 7: the shared module's commonTest runs as Android *unit* tests on
// the host JVM, and with the Rust cdylib reachable they construct the REAL
// FausteEngine — the same end-to-end exercise the Rust host tests do, with
// the engine falling back to the Null backend off-Android. The
// buildHostCdylib task below builds that cdylib before any test task —
// nothing else in the Gradle build produces the host artifact (composeApp's
// buildRust cross-compiles the ANDROID targets with cargo-ndk only) — and
// two JNA search properties make loading it work; neither may leak into the
// app (both are set on the unit-test tasks only):
//
// 1. jna.library.path -> the Rust workspace target/debug, where
//    `cargo build -p fp-backends-mobile --features uniffi` — buildHostCdylib's
//    command, the same feature-flagged build CI's drift-check runs — puts
//    libfp_backends_mobile.so. The plain `cargo build -p` (no features)
//    does NOT export the FFI surface (the uniffi proc-macro scaffolding is
//    cfg-gated on the feature). The generated bindings resolve the name via
//    findLibraryName("fauste_player") == "fp_backends_mobile", i.e. JNA
//    loads libfp_backends_mobile.so on Linux hosts.
// 2. jna.boot.library.path -> a build dir holding JNA's OWN host dispatch
//    library (libjnidispatch.so, linux-x86-64). The `@aar` artifact used in
//    commonMain ships Android-ABI natives under jni/ only — its classes.jar
//    carries empty directories where the desktop jars bundle the dispatch
//    .so — so on the host JVM JNA cannot extract its dispatch library from
//    the AAR. The extractJnaHostDispatch task pulls the plain (non-AAR)
//    jna jar through a detached configuration — so the jar never enters any
//    compile/runtime classpath and the app keeps the AAR — and unzips the
//    one host native into that dir.
val jnaHostDispatchDir = layout.buildDirectory.dir("jna-host-dispatch")

val extractJnaHostDispatch by tasks.registering {
    val jnaVersion = libs.versions.jna.get()
    val output = jnaHostDispatchDir
    outputs.dir(output)
    doLast {
        val target = output.get().asFile.apply { mkdirs() }
        // A detached configuration: resolved only here, never published or
        // wired into a classpath.
        val jnaJar =
            project.configurations
                .detachedConfiguration(project.dependencies.create("net.java.dev.jna:jna:$jnaVersion"))
                .resolve()
                .single()
        // Plain zip extraction (no Gradle copy DSL): the one host native the
        // AAR does not ship, written flat into the target dir.
        ZipFile(jnaJar).use { zip ->
            val entry =
                zip.getEntry("com/sun/jna/linux-x86-64/libjnidispatch.so")
                    ?: error("the jna jar at $jnaJar has no linux-x86-64 dispatch library")
            zip.getInputStream(entry).use { input ->
                File(target, "libjnidispatch.so").outputStream().use { fileOut -> input.copyTo(fileOut) }
            }
        }
    }
}

// The host cdylib the tests load. Mirrors composeApp's buildRust cargo
// resolution (absolute ~/.cargo/bin path — the Gradle daemon's PATH does
// not carry it) and its configuration-time hoisting; unlike buildRust this
// builds the HOST target, not the Android ones.
val cargoHome: String =
    System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"

// Resolved at configuration time so the task is configuration-cache friendly
// (no project/rootProject lookups inside task actions).
val rustRoot: File = rootProject.file(".")
val hostCdylib: File = File(rustRoot, "target/debug/libfp_backends_mobile.so")

val buildHostCdylib by tasks.registering(Exec::class) {
    group = "rust"
    description =
        "Builds the fp-backends-mobile HOST cdylib (target/debug) that the " +
            "shared module's real-engine unit tests load through JNA."

    workingDir(rustRoot)

    // Declared inputs/outputs so Gradle up-to-date checks skip the cargo
    // build when nothing in the Rust workspace changed (the same input set
    // as composeApp's buildRust; cargo's own incrementality does the rest).
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
    outputs.file(hostCdylib).withPropertyName("host-cdylib")

    // cargo lives in ~/.cargo/bin, which is not on the default PATH of this
    // machine. Gradle resolves the executable against the daemon's PATH
    // (not the environment set below), so the absolute path is mandatory;
    // the PATH override keeps cargo's own toolchain lookups working.
    environment("PATH", "$cargoHome/bin:${System.getenv("PATH")}")
    commandLine(
        "$cargoHome/bin/cargo", "build",
        "-p", "fp-backends-mobile",
        "--features", "uniffi",
    )
}

tasks.withType<Test>().configureEach {
    // The real-engine tests load the host cdylib JNA resolves through
    // jna.library.path; build it first. Gradle skips the task as UP-TO-DATE
    // when its declared inputs are unchanged, and cargo's own incrementality
    // makes unavoidable re-runs cheap. Nothing outside the Test tasks
    // depends on it, so assembleDebug and friends never trigger a Rust build.
    dependsOn(buildHostCdylib)
    dependsOn(extractJnaHostDispatch)
    systemProperty("jna.library.path", rootProject.layout.projectDirectory.dir("target/debug").asFile.absolutePath)
    systemProperty("jna.boot.library.path", jnaHostDispatchDir.get().asFile.absolutePath)
}
