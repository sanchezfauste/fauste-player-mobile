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
                // TODO(phase-1): switch androidMain to the JNA AAR (net.java.dev.jna:jna@aar) —
                // the plain jar ships no Android libjnidispatch.so, so any real FFI call on a
                // device will fail until then. Phase 0 makes no FFI calls.
                implementation(libs.jna)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
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
