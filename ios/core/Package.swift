// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MessengerCore",
    products: [
        .library(name: "MessengerCore", targets: ["MessengerCore"]),
    ],
    targets: [
        .target(name: "MessengerCore"),
        .testTarget(name: "MessengerCoreTests", dependencies: ["MessengerCore"]),
    ],
    swiftLanguageModes: [.v5]
)
