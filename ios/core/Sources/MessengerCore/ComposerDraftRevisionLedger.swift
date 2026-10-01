public struct ComposerDraftRevisionLedger {
    private var revisions: [String: UInt64] = [:]

    public init() {}

    @discardableResult
    public mutating func advance(for roomId: String) -> UInt64 {
        let next = (revisions[roomId] ?? 0) &+ 1
        revisions[roomId] = next
        return next
    }

    public func current(for roomId: String) -> UInt64 {
        revisions[roomId] ?? 0
    }

    public func isCurrent(_ revision: UInt64, for roomId: String) -> Bool {
        current(for: roomId) == revision
    }
}

public struct OrderedSubmissionBuffer<Element> {
    private var values: [Element] = []

    public init() {}

    public var isEmpty: Bool { values.isEmpty }

    public mutating func enqueue(_ value: Element) {
        values.append(value)
    }

    public mutating func dequeue() -> Element? {
        guard !values.isEmpty else { return nil }
        return values.removeFirst()
    }
}
