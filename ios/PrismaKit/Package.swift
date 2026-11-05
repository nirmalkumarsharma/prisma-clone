// swift-tools-version:5.9
import PackageDescription

// Pure-Swift filter engine (no UIKit), so it builds and tests on macOS, iOS and Linux.
let package = Package(
    name: "PrismaKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "PrismaKit", targets: ["PrismaKit"]),
    ],
    targets: [
        .target(name: "PrismaKit"),
        .testTarget(name: "PrismaKitTests", dependencies: ["PrismaKit"]),
    ]
)
