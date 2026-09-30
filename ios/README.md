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
xtool dev build
xtool dev
```

`xtool dev build` checks the real app and pinned Matrix XCFramework. `xtool dev` additionally signs and installs to a paired iPhone. Device pairing, Developer Mode, and certificate trust are completed on the phone when prompted.

Development configuration leaves push disabled and keeps `NSFileProtectionComplete`. Push-enabled signing needs a separately provisioned Apple capability and matching push configuration. A free provisioning profile is suitable for short development runs, not dependable distribution.
