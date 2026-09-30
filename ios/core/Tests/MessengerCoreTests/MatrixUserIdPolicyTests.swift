import XCTest
@testable import MessengerCore

final class MatrixUserIdPolicyTests: XCTestCase {
    func testTrimsCompleteMatrixIdsAndAcceptsServerPort() {
        XCTAssertEqual(MatrixUserIdPolicy.normalize("  @alice:example.org  "), "@alice:example.org")
        XCTAssertEqual(MatrixUserIdPolicy.normalize("@alice:example.org:8448"), "@alice:example.org:8448")
    }

    func testRejectsIncompleteOrAmbiguousMatrixIds() {
        for value in ["", "alice:example.org", "@:example.org", "@alice:", "@alice:example.org extra"] {
            XCTAssertNil(MatrixUserIdPolicy.normalize(value), value)
        }
    }
}
