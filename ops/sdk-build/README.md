# Matrix Rust SDK FFI patch

This patch adds the peer-device trust query required before call keys can be sent. It is deliberately a source patch, not a shippable SDK binary. The app must not turn on calls until regenerated Android and Swift bindings from one compatible SDK revision include this method and both platforms pass the call-key acceptance checks in `docs/CALLS.md`.

## Pinned source

- Repository: `https://github.com/matrix-org/matrix-rust-sdk.git`
- Revision: `2a3db80e99fe322b9849325a182dc8a634fb20d4`
- Patch: `patches/0001-ffi-verified-cross-signed-device-ids.patch`

## Reproduce and check the patch

From the repository root, in PowerShell:

```powershell
$sdk = Join-Path $env:TEMP 'friendline-matrix-rust-sdk'
git clone https://github.com/matrix-org/matrix-rust-sdk.git $sdk
git -C $sdk checkout --detach 2a3db80e99fe322b9849325a182dc8a634fb20d4
git -C $sdk apply --check (Join-Path (Get-Location) 'ops/sdk-build/patches/0001-ffi-verified-cross-signed-device-ids.patch')
git -C $sdk apply (Join-Path (Get-Location) 'ops/sdk-build/patches/0001-ffi-verified-cross-signed-device-ids.patch')
cargo fmt --manifest-path (Join-Path $sdk 'Cargo.toml') --all -- --check
cargo test --manifest-path (Join-Path $sdk 'Cargo.toml') -p matrix-sdk-ffi --lib verified_cross_signed_device_ids_tests
```

The patch must be reviewed, formatted, and compiled before generating bindings or distributing an app. Native packaging additionally needs the Android NDK for all shipped ABIs and a Mac with Xcode for the iOS XCFramework. A compatible Swift wrapper revision must first be identified; the currently pinned iOS wrapper predates the encrypted custom to-device API used for this design.

## Rebuild the Android wrapper

The Android Kotlin wrapper is pinned to `matrix-rust-components-kotlin` commit `3b187eecc2b30f0dfad594be0ce13acf4e885a9c` (tag `sdk-v26.09.28`). On Windows, install Rust targets for all four ABIs, `cargo-ndk`, Android NDK r27b, and `protoc`; set `ANDROID_NDK_HOME` and `ANDROID_HOME` to the NDK/SDK locations. From PowerShell:

```powershell
$wrapper = Join-Path $env:TEMP 'friendline-matrix-kotlin'
git clone https://github.com/matrix-org/matrix-rust-components-kotlin.git $wrapper
git -C $wrapper checkout --detach 3b187eecc2b30f0dfad594be0ce13acf4e885a9c

$rust = Join-Path $env:TEMP 'friendline-matrix-rust-sdk'
git clone https://github.com/matrix-org/matrix-rust-sdk.git $rust
git -C $rust checkout --detach 2a3db80e99fe322b9849325a182dc8a634fb20d4
git -C $rust apply (Join-Path (Get-Location) 'ops/sdk-build/patches/0001-ffi-verified-cross-signed-device-ids.patch')

Push-Location $rust
try {
  cargo xtask kotlin build-android-library --profile small-release-stripped `
    --src-dir (Join-Path $wrapper 'sdk/sdk-android/src/main') --package full-sdk
} finally { Pop-Location }
Push-Location $wrapper
try { .\gradlew.bat :sdk:sdk-android:assembleRelease } finally { Pop-Location }
Copy-Item (Join-Path $wrapper 'sdk/sdk-android/build/outputs/aar/sdk-android-release.aar') `
  'app/libs/sdk-android-private-2a3db80.aar'
python ops/sdk-build/strip_android_aar.py 'app/libs/sdk-android-private-2a3db80.aar' `
  --llvm-strip (Join-Path $env:ANDROID_NDK_HOME 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe')
```

Add the installed Rust and `cargo-ndk` binaries to `PATH` before running these commands. The main Rust checkout must use the patch and the Kotlin wrapper must be at its pinned revision so generated Kotlin declarations match the native symbols. The local builds used separate temporary paths because the GNU linker tools on Windows require paths without spaces.

## Current Android build artifact

The private Android wrapper was built from this revision and patch for `aarch64-linux-android`, `armv7-linux-androideabi`, `i686-linux-android`, and `x86_64-linux-android`, then packaged as `sdk-android-release.aar`. Before app packaging, `strip_android_aar.py` uses the pinned Android NDK's `llvm-strip --strip-all` on the four FFI libraries. This removes non-runtime ELF symbol tables while retaining each library's `.dynsym` exports. Run it against the generated AAR with the matching NDK tool, for example:

```powershell
$ndk = 'C:\Android\Sdk\ndk\27.2.12479018'
python ops/sdk-build/strip_android_aar.py app/libs/sdk-android-private-2a3db80.aar `
  --llvm-strip "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
```

The stripped local app copy is `app/libs/sdk-android-private-2a3db80.aar`; it is intentionally ignored by Git because it is about 104 MB. Its SHA-256 is `052D255692E5E6516A46AD62B8331687333F191008609EF32C8404B42ED3628C`. Rebuild it from the pinned source, patch, wrapper generation, and strip step before building the app from a fresh checkout.

On Windows, the verified build used Rust stable with a complete MinGW-w64 toolchain and Android NDK r27b, because this host has no MSVC linker. The Rust GNU toolchain, NDK, and Gradle output were kept outside paths containing spaces where the native toolchain required it. The patched Android build compiled the FFI for all four ABIs, generated `verifiedCrossSignedDeviceIdsForUser(userId)`, and produced the release AAR. The two focused Rust unit tests passed.

## Security contract

The query forces a fresh user-identity/key query, errors on absent or unsafe cross-signing identity, and returns only device IDs that the SDK marks cross-signed. The caller must send each secret only to explicit returned device IDs through encrypted to-device Olm delivery. Wildcard device selection, room timeline events, push payloads, and the LiveKit token service are not valid call-key transports. An empty device list must fail the call closed.

This patch supplies the missing verified-device lookup. The Android wrapper is locally generated and the Android call-key sender uses explicit trusted devices. The patch does not generate the required iOS wrapper, complete the call-token/LiveKit UI flow, or satisfy end-to-end call acceptance.
