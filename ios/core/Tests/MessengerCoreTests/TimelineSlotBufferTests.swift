import XCTest
@testable import MessengerCore

final class TimelineSlotBufferTests: XCTestCase {
    func testIndexedUpdatesRetainNonMessageTimelineSlots() {
        var buffer = TimelineSlotBuffer<String>()
        buffer.apply(.reset([nil, "one", nil, "two"]))

        buffer.apply(.insert(index: 2, value: "between"))
        XCTAssertEqual(buffer.visible(limit: 10), ["one", "between", "two"])

        buffer.apply(.set(index: 3, value: "three"))
        XCTAssertEqual(buffer.visible(limit: 10), ["one", "between", "three", "two"])

        buffer.apply(.remove(index: 1))
        XCTAssertEqual(buffer.visible(limit: 10), ["between", "three", "two"])
    }

    func testVisibleLimitDoesNotDiscardOlderIndexedSlots() {
        var buffer = TimelineSlotBuffer<String>()
        buffer.apply(.reset(["one", nil, "two", "three"]))

        XCTAssertEqual(buffer.visible(limit: 2), ["two", "three"])
        buffer.apply(.set(index: 0, value: "updated older event"))

        XCTAssertEqual(buffer.slotCount, 4)
        XCTAssertEqual(buffer.visible(limit: 2), ["two", "three"])
        XCTAssertEqual(buffer.visible(limit: 10), ["updated older event", "two", "three"])
    }

    func testPushPopAndClearPreserveSdkIndexSemantics() {
        var buffer = TimelineSlotBuffer<String>()
        buffer.apply(.reset([nil, "middle", nil]))

        buffer.apply(.pushFront("older"))
        buffer.apply(.pushBack("newer"))
        XCTAssertEqual(buffer.visible(limit: 10), ["older", "middle", "newer"])

        buffer.apply(.popFront)
        buffer.apply(.popBack)
        XCTAssertEqual(buffer.visible(limit: 10), ["middle"])
        XCTAssertEqual(buffer.slotCount, 3)

        buffer.apply(.clear)
        XCTAssertEqual(buffer.slotCount, 0)
        XCTAssertEqual(buffer.visible(limit: 10), [])
    }
}
