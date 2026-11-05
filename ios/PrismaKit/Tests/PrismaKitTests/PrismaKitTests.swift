import Foundation
import XCTest
@testable import PrismaKit

/// Checks the Swift port against reference images produced by the JS engine (`npm run fixtures`).
final class PrismaKitTests: XCTestCase {
    struct Meta: Decodable {
        let width: Int
        let height: Int
        let seed: Int
        let prompts: [String: String]
    }

    static let fixtures = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().appendingPathComponent("../../../../fixtures").standardized

    lazy var meta: Meta = try! JSONDecoder().decode(
        Meta.self, from: Data(contentsOf: Self.fixtures.appendingPathComponent("fixtures.json")))

    func fixture(_ name: String) throws -> [UInt8] {
        [UInt8](try Data(contentsOf: Self.fixtures.appendingPathComponent("\(name).rgba")))
    }

    /// Tiny float differences can flip a smoothstep edge, so allow a few stray pixels.
    func assertClose(_ expected: [UInt8], _ actual: [UInt8], _ label: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(expected.count, actual.count, file: file, line: line)
        var sum = 0, bad = 0
        for i in expected.indices where i % 4 != 3 {
            let d = abs(Int(expected[i]) - Int(actual[i]))
            sum += d
            if d > 8 { bad += 1 }
        }
        let channels = Double(expected.count / 4 * 3)
        let mean = Double(sum) / channels, badShare = Double(bad) / channels
        XCTAssert(mean < 0.5 && badShare < 0.005,
                  "\(label) differs from JS: mean Δ=\(mean), Δ>8 on \(badShare * 100)%", file: file, line: line)
    }

    func testSampleSceneMatchesJs() throws {
        XCTAssertEqual(try fixture("sample"), SampleScene.render(width: meta.width, height: meta.height).rgba)
    }

    func testEveryFilterMatchesJs() throws {
        let src = SampleScene.render(width: meta.width, height: meta.height)
        for f in Prisma.filters {
            assertClose(try fixture(f.id), try Prisma.apply(f.id, to: src, seed: meta.seed).rgba, f.id)
        }
    }

    func testIntensityBlendMatchesJs() throws {
        let src = SampleScene.render(width: meta.width, height: meta.height)
        assertClose(try fixture("candy-60"), try Prisma.apply("candy", to: src, intensity: 0.6, seed: meta.seed).rgba, "candy@60%")
    }

    func testIntensityZeroIsOriginal() throws {
        let src = SampleScene.render(width: meta.width, height: meta.height)
        XCTAssertEqual(try Prisma.apply("wave", to: src, intensity: 0).rgba, src.rgba)
    }

    func testPromptsMatchJs() throws {
        for f in Prisma.filters {
            XCTAssertEqual(try Prisma.buildPrompt(f.id, subject: "  a lighthouse at dusk.  "), meta.prompts[f.id])
        }
        XCTAssertEqual(try Prisma.buildPrompt("wave", subject: "   "), meta.prompts["empty"])
    }

    func testUnknownFilterThrows() {
        XCTAssertThrowsError(try Prisma.filter("nope")) { XCTAssertEqual($0 as? PrismaError, .unknownFilter("nope")) }
    }

    func testDeterministicAndSizePreserving() throws {
        let src = SampleScene.render(width: 50, height: 37)
        for f in Prisma.filters {
            let a = try Prisma.apply(f.id, to: src, seed: 9)
            XCTAssertEqual(a.width, 50)
            XCTAssertEqual(a.height, 37)
            XCTAssertEqual(a.rgba, try Prisma.apply(f.id, to: src, seed: 9).rgba)
        }
    }
}
