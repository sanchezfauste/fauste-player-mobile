# Release minification rules for the app's TWO reflection-sensitive
# dependencies (Phase 5, Task 6). Everything else on the classpath ships
# its own consumer rules or needs none:
#   - media3-common 1.11.1 ships proguard.txt (verified: -dontwarn set)
#     and media3-session ships none because it needs none;
#   - androidx.lifecycle / activity / Compose ship complete consumer
#     rules (the androidx baseline profile);
#   - Koin 4.0.0's graph is built from constructor lambdas in code
#     (no reflective construction), so nothing needs keeping;
#   - the app's own classes need no rules — R8 keeps every manifest
#     entry point (Application, Activity, Service) and everything
#     reachable from them.
# The rules below are the ones the classpath does NOT provide, each
# verified against the actual artifact (see the comments) and proven
# end-to-end by the release smoke run on the emulator (docs/release/
# measurements.md records the verification).

# --- UniFFI-generated Kotlin bindings (org.fauste.player.rust) ----------
# uniffi-bindgen 0.28 generates NO consumer rules and its docs prescribe
# none (verified: docs/manual/src/kotlin/ on the v0.28.3 tag has no
# R8/ProGuard section). The bindings package must be kept WHOLESALE:
#   - UniffiLib's methods are resolved BY NAME: JNA's Native.load
#     dynamic proxy maps each interface method to the native symbol of
#     the same name (uniffi_fp_backends_mobile_fn_...), so an R8 rename
#     is an unsatisfiable symbol lookup at first FFI touch;
#   - RustBuffer extends com.sun.jna.Structure: JNA discovers its
#     @JvmField fields reflectively BY NAME (they ARE the native struct
#     members capacity/len/data), so a rename corrupts the struct
#     layout silently;
#   - the Uniffi*Callback interfaces (com.sun.jna.Callback subtypes) are
#     invoked reflectively by JNA's callback machinery.
# The file is generated code that regenerates on every ffi.rs change,
# so hand-tuning keeps inside it is unmaintainable — keep it all.
-keep class org.fauste.player.rust.** { *; }

# --- JNA (net.java.dev.jna:jna:5.19.1 AAR) ------------------------------
# VERIFIED: the jna-5.19.1.aar ships NO consumer proguard.txt (archive
# contents: classes.jar, jni/*, res/* only — a direct inspection, so the
# "the JNA AAR ships consumer rules" assumption is FALSE for this
# version). The app therefore keeps JNA's own reflection surface itself:
# Structure field discovery, Library interface proxies, Callback
# invocation and the native registration all reflect into these
# classes; an obfuscated JNA fails at the first Native.load.
-keep class com.sun.jna.** { *; }

# JNA references optional JDK/android internals defensively guarded at
# runtime (ClassValue, sun.misc.Unsafe fallbacks) — unresolved at
# compile time, never on the code paths we exercise.
-dontwarn com.sun.jna.**
