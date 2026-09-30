# iOS build and device workflow

This app uses SwiftPM and xtool for iOS builds and device deployment. The package targets iOS 16 and pins the Matrix Rust SDK to the version used by the app sources. The messenger target omits the deferred calling modules.

## One-time WSL setup

Use Swift 6.4.0 and xtool 1.20.1. Install xtool's matching `xtool-x86_64.AppImage` release into `~/.local/bin/xtool` and make it executable. On a different Linux architecture, use the matching release asset.

Install the Darwin Swift SDK from an Xcode 27 archive using the xtool setup flow:

```sh
xtool setup
swift sdk list
```

Apple hosts the Xcode archive behind Apple ID sign-in and a license step. The credentials are entered locally into xtool's setup flow; never place them in this repository. From Windows, follow xtool's WSL USB passthrough instructions before connecting an iPhone.

## Build and install

From `ios/` in WSL:

```sh
swift package resolve
python3 configure_xtool_profile.py
xtool dev build
xtool dev
```

`xtool dev build` checks the real app and pinned Matrix XCFramework. `xtool dev` additionally signs and installs to a paired iPhone. Device pairing, Developer Mode, and certificate trust are completed on the phone when prompted.

## macOS helper workflow

For a build or device check from a Mac, use the same SwiftPM app and xtool commands; do not switch to the legacy XcodeGen project. Install Swift 6.4.0 and xtool 1.20.1 using the [official macOS installation guide](https://xtool.sh/documentation/xtool/installation-macos/). From the repository checkout:

```sh
cd ios
swift test --package-path core
xtool setup
swift package resolve
python3 configure_xtool_profile.py
xtool devices
xtool dev build
xtool dev
```

`xtool setup` and device provisioning use the helper's Apple account locally. Never send Apple credentials, signing keys, device tokens, or unredacted account/device details with the test results. `xtool dev build` is the app/SDK compile gate; `xtool dev` signs, installs, and launches on the selected iPhone.

For a useful handoff, report the tested branch and commit, Swift and xtool versions, iPhone model/iOS version, and pass/fail for core tests, app build, install, launch, sign-in, and session restore. Include only the relevant sanitized error if a step fails. Cross-platform messaging acceptance additionally needs both clients to reach the same disposable Synapse server; a Mac build by itself does not close that gate.

The profile generator writes its non-secret Info.plist and entitlements under the ignored `.build/xtool-profile/` directory. Run it before each xtool build; its default resets push to off. After APNs provisioning is configured, a push-enabled development profile can be generated with `python3 configure_xtool_profile.py --configuration debug --push-domain matrix.example.org`. For a release profile, pair `python3 configure_xtool_profile.py --configuration release --push-domain matrix.example.org` with `xtool dev build --configuration release` or `xtool dev run --configuration release`. Run the generator again without `--push-domain` to return to a push-disabled build.

Development configuration leaves push disabled and keeps `NSFileProtectionComplete`. Push-enabled signing needs a separately provisioned Apple capability and matching push configuration. A free provisioning profile is suitable for short development runs, not dependable distribution.
