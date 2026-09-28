# Preserve the Matrix Rust SDK's generated UniFFI bindings and native entry points.
-keep class org.matrix.rustcomponents.sdk.** { *; }
-keep class uniffi.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
