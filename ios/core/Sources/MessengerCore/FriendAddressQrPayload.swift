import Foundation

public struct FriendAddressQrPayload: Equatable {
    public static let scheme = "friendline"
    public static let host = "friend"

    public let matrixId: String
    public let homeserverUrl: String

    public static func encode(matrixId: String, homeserverUrl: String, allowDevelopmentHTTP: Bool = false) throws -> String {
        guard isValidMatrixId(matrixId) else { throw FriendAddressQrError.invalidMatrixId }
        let normalizedHomeserver = try normalizeHomeserver(homeserverUrl, allowDevelopmentHTTP: allowDevelopmentHTTP)
        let queryItems = [
            ("v", "1"),
            ("matrix_id", matrixId),
            ("homeserver", normalizedHomeserver),
        ]
        let query = try queryItems.map { item in
            "\(try encodeComponent(item.0))=\(try encodeComponent(item.1))"
        }.joined(separator: "&")
        return "\(scheme)://\(host)?\(query)"
    }

    public static func parse(_ rawValue: String, allowDevelopmentHTTP: Bool = false) throws -> Self {
        guard rawValue.utf8.count <= 2_048,
              let components = URLComponents(string: rawValue),
              components.scheme?.lowercased() == scheme,
              components.host?.lowercased() == host,
              components.path.isEmpty,
              components.port == nil,
              components.user == nil,
              components.password == nil,
              components.fragment == nil,
              let items = components.queryItems,
              items.count == 3 else { throw FriendAddressQrError.invalidPayload }

        let grouped = Dictionary(grouping: items, by: \.name)
        guard Set(grouped.keys) == Set(["v", "matrix_id", "homeserver"]),
              grouped.values.allSatisfy({ $0.count == 1 }),
              grouped["v"]?.first?.value == "1",
              let matrixId = grouped["matrix_id"]?.first?.value,
              isValidMatrixId(matrixId),
              let homeserver = grouped["homeserver"]?.first?.value else {
            throw FriendAddressQrError.invalidPayload
        }

        return Self(
            matrixId: matrixId,
            homeserverUrl: try normalizeHomeserver(homeserver, allowDevelopmentHTTP: allowDevelopmentHTTP)
        )
    }

    public func resolve(forHomeserver currentHomeserver: String, allowDevelopmentHTTP: Bool = false) throws -> String {
        let current = try Self.normalizeHomeserver(currentHomeserver, allowDevelopmentHTTP: allowDevelopmentHTTP)
        guard Self.sameHomeserver(homeserverUrl, current) else { throw FriendAddressQrError.homeserverMismatch }
        return matrixId
    }

    private static func isValidMatrixId(_ value: String) -> Bool {
        guard value.utf8.count <= 255, value.first == "@", !value.contains(where: \.isWhitespace),
              let separator = value.firstIndex(of: ":"), separator > value.startIndex else { return false }
        return value.index(after: separator) < value.endIndex
    }

    private static func encodeComponent(_ value: String) throws -> String {
        var allowed = CharacterSet()
        allowed.insert(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        guard let encoded = value.addingPercentEncoding(withAllowedCharacters: allowed) else {
            throw FriendAddressQrError.invalidPayload
        }
        return encoded
    }

    private static func normalizeHomeserver(_ value: String, allowDevelopmentHTTP: Bool) throws -> String {
        var normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        while normalized.hasSuffix("/") { normalized.removeLast() }
        guard let components = URLComponents(string: normalized),
              let scheme = components.scheme?.lowercased(),
              let host = components.host?.lowercased(),
              !host.isEmpty,
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil,
              components.url != nil,
              components.port.map({ (1...65_535).contains($0) }) ?? true else {
            throw FriendAddressQrError.invalidHomeserver
        }

        let isSecure = scheme == "https"
        let isDevelopmentHTTP = allowDevelopmentHTTP && scheme == "http" && isDevelopmentHost(host)
        guard isSecure || isDevelopmentHTTP else { throw FriendAddressQrError.invalidHomeserver }
        return normalized
    }

    private static func sameHomeserver(_ lhs: String, _ rhs: String) -> Bool {
        guard let left = URLComponents(string: lhs), let right = URLComponents(string: rhs) else { return false }
        let leftPath = left.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let rightPath = right.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return left.scheme?.caseInsensitiveCompare(right.scheme ?? "") == .orderedSame &&
            left.host?.caseInsensitiveCompare(right.host ?? "") == .orderedSame &&
            effectivePort(left) == effectivePort(right) && leftPath == rightPath
    }

    private static func effectivePort(_ components: URLComponents) -> Int? {
        if let port = components.port { return port }
        switch components.scheme?.lowercased() {
        case "https": return 443
        case "http": return 80
        default: return nil
        }
    }

    private static func isDevelopmentHost(_ host: String) -> Bool {
        if host == "localhost" || host.hasSuffix(".localhost") || host == "::1" { return true }
        let octets = host.split(separator: ".").compactMap { Int($0) }
        guard octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) else { return false }
        return octets[0] == 10 || octets[0] == 127 || (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && (16...31).contains(octets[1]))
    }
}

public enum FriendAddressQrError: Error, Equatable {
    case invalidMatrixId
    case invalidHomeserver
    case invalidPayload
    case homeserverMismatch
}
