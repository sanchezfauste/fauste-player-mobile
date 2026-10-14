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
// the engine falling back to the Null backend off-Android. Two JNA search
// properties make that work; neither may leak into the app (both are set on
// the unit-test tasks only):
//
// 1. jna.library.path -> the Rust workspace target/debug, where
//    `cargo build -p fp-backends-mobile --features uniffi` puts
//    libfp_backends_mobile.so. The plain `cargo build -p` (no features)
//    does NOT export the FFI surface (the uniffi proc-macro scaffolding is
//    cfg-gated on the feature), so CI's drift-check command is the one to
//    mirror. The generated bindings resolve the name via
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

tasks.withType<Test>().configureEach {
    dependsOn(extractJnaHostDispatch)
    systemProperty("jna.library.path", rootProject.layout.projectDirectory.dir("target/debug").asFile.absolutePath)
    systemProperty("jna.boot.library.path", jnaHostDispatchDir.get().asFile.absolutePath)
}
