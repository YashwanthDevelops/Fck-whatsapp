public struct TimelineSlotBuffer<Element> {
    public enum Mutation {
        case append([Element?])
        case clear
        case pushFront(Element?)
        case pushBack(Element?)
        case popFront
        case popBack
        case insert(index: Int, value: Element?)
        case set(index: Int, value: Element?)
        case remove(index: Int)
        case truncate(length: Int)
        case reset([Element?])
    }

    private var slots: [Element?] = []

    public init() {}

    public var slotCount: Int { slots.count }

    public mutating func apply(_ mutation: Mutation) {
        switch mutation {
        case let .append(values):
            slots.append(contentsOf: values)
        case .clear:
            slots.removeAll(keepingCapacity: true)
        case let .pushFront(value):
            slots.insert(value, at: 0)
        case let .pushBack(value):
            slots.append(value)
        case .popFront:
            if !slots.isEmpty { slots.removeFirst() }
        case .popBack:
            if !slots.isEmpty { slots.removeLast() }
        case let .insert(index, value):
            slots.insert(value, at: min(max(index, 0), slots.count))
        case let .set(index, value):
            guard slots.indices.contains(index) else { return }
            slots[index] = value
        case let .remove(index):
            guard slots.indices.contains(index) else { return }
            slots.remove(at: index)
        case let .truncate(length):
            slots = Array(slots.prefix(max(length, 0)))
        case let .reset(values):
            slots = values
        }
    }

    public func visible(limit: Int) -> [Element] {
        guard limit > 0 else { return [] }
        return Array(slots.compactMap { $0 }.suffix(limit))
    }
}
