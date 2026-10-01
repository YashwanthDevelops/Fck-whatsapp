public struct PushNotificationRoute: Equatable, Identifiable {
    public let roomId: String
    public let eventId: String?

    public var id: String { "\(roomId)|\(eventId ?? "")" }

    public init(roomId: String, eventId: String?) {
        self.roomId = roomId
        self.eventId = eventId
    }
}

public enum PushNotificationRoutePolicy {
    private static let roomIdPattern = #"^![A-Za-z0-9._=+\-]+:[A-Za-z0-9.\-:\[\]]{1,255}$"#
    private static let eventIdPattern = #"^\$[A-Za-z0-9._=:/+\-]{1,500}$"#

    public static func parse(roomId: String?, eventId: String?) -> PushNotificationRoute? {
        guard let roomId, roomId.range(of: roomIdPattern, options: .regularExpression) != nil else {
            return nil
        }
        let safeEventId = eventId.flatMap { value in
            value.range(of: eventIdPattern, options: .regularExpression) == nil ? nil : value
        }
        return PushNotificationRoute(roomId: roomId, eventId: safeEventId)
    }
}
