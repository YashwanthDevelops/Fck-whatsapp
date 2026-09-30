// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PrivateMessenger",
    platforms: [
        .iOS(.v16),
    ],
    products: [
        .library(
            name: "PrivateMessenger",
            targets: ["PrivateMessenger"]
        ),
    ],
    dependencies: [
        .package(path: "core"),
        .package(
            url: "https://github.com/matrix-org/matrix-rust-components-swift",
            exact: "26.09.07"
        ),
    ],
    targets: [
        .target(
            name: "PrivateMessenger",
            dependencies: [
                .product(name: "MessengerCore", package: "core"),
                .product(name: "MatrixRustSDK", package: "matrix-rust-components-swift"),
            ],
            path: "Sources",
            exclude: [
                "CallAuthClient.swift",
                "LiveKitCallMediaSession.swift",
            ],
            resources: [
                .process("en.lproj"),
            ]
        ),
    ],
    swiftLanguageModes: [.v5]
)
