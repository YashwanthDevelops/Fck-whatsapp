import XCTest
@testable import MessengerCore

final class TextSendOrderingRegressionTests: XCTestCase {
    func testRapidSubmissionsStayInTapOrder() {
        var queue = OrderedSubmissionBuffer<String>()
        queue.enqueue("first")
        queue.enqueue("second")
        queue.enqueue("third")

        XCTAssertEqual(queue.dequeue(), "first")
        XCTAssertEqual(queue.dequeue(), "second")
        XCTAssertEqual(queue.dequeue(), "third")
        XCTAssertNil(queue.dequeue())
    }

    func testOlderSendCompletionCannotOwnANewerComposerDraft() {
        var revisions = ComposerDraftRevisionLedger()
        let sentRevision = revisions.advance(for: "!room:example.org")
        let newerDraftRevision = revisions.advance(for: "!room:example.org")

        XCTAssertFalse(revisions.isCurrent(sentRevision, for: "!room:example.org"))
        XCTAssertTrue(revisions.isCurrent(newerDraftRevision, for: "!room:example.org"))
        XCTAssertFalse(revisions.isCurrent(sentRevision, for: "!other:example.org"))
        XCTAssertEqual(revisions.current(for: "!other:example.org"), 0)
    }
}
