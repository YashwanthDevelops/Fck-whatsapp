import XCTest
@testable import MessengerCore

final class SyncRecoveryRetryPolicyTests: XCTestCase {
    func testRetryDelayGrowsAndCapsAtThirtySeconds() {
        XCTAssertEqual(SyncRecoveryRetryPolicy.delaySeconds(forAttempt: 0), 1)
        XCTAssertEqual(SyncRecoveryRetryPolicy.delaySeconds(forAttempt: 1), 2)
        XCTAssertEqual(SyncRecoveryRetryPolicy.delaySeconds(forAttempt: 2), 4)
        XCTAssertEqual(SyncRecoveryRetryPolicy.delaySeconds(forAttempt: 5), 30)
        XCTAssertEqual(SyncRecoveryRetryPolicy.delaySeconds(forAttempt: 12), 30)
    }

    func testNegativeAttemptUsesInitialDelay() {
        XCTAssertEqual(SyncRecoveryRetryPolicy.delaySeconds(forAttempt: -1), 1)
    }
}
