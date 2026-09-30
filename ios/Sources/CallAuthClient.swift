import Foundation

/// Short-lived credentials returned by the private Matrix-membership-gated call service.
/// The LiveKit token is never persisted and the Matrix access token is never logged.
struct CallAuthCredentials: Decodable, Sendable {
    let callId: String
    let url: String
    let token: String
    let expiresAt: Int64

    enum CodingKeys: String, CodingKey {
        case callId = "call_id"
        case url
        case token
        case expiresAt = "expires_at"
    }
}

enum CallAuthClientError: Error {
    case invalidHomeserver
    case invalidSession
    case rejected(Int)
    case malformedResponse
    case expired
}

/// Calls the route exposed on the configured homeserver origin. Redirects are refused so
/// a Matrix bearer token can never be forwarded to a different authority.
struct CallAuthClient: Sendable {
    private let session: URLSession

    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 8
        configuration.timeoutIntervalForResource = 10
        session = URLSession(
            configuration: configuration,
            delegate: NoRedirectSessionDelegate(),
            delegateQueue: nil
        )
    }

    func createCall(
        homeserverURL: String,
        accessToken: String,
        roomID: String
    ) async throws -> CallAuthCredentials {
        try await request(
            homeserverURL: homeserverURL,
            accessToken: accessToken,
            roomID: roomID,
            callID: nil
        )
    }

    func joinCall(
        homeserverURL: String,
        accessToken: String,
        roomID: String,
        callID: String
    ) async throws -> CallAuthCredentials {
        guard Self.isValidCallID(callID) else { throw CallAuthClientError.malformedResponse }
        return try await request(
            homeserverURL: homeserverURL,
            accessToken: accessToken,
            roomID: roomID,
            callID: callID
        )
    }

    private func request(
        homeserverURL: String,
        accessToken: String,
        roomID: String,
        callID: String?
    ) async throws -> CallAuthCredentials {
        guard !accessToken.isEmpty, accessToken.utf8.count <= 4_096,
              !roomID.isEmpty, roomID.utf8.count <= 255 else {
            throw CallAuthClientError.invalidSession
        }
        guard let base = Self.homeserverOrigin(homeserverURL) else {
            throw CallAuthClientError.invalidHomeserver
        }
        var components = URLComponents(url: base, resolvingAgainstBaseURL: false)
        let callPath = callID.map { "/" + $0 } ?? ""
        components?.path = "/_friendline/calls/v1/calls" + callPath
        guard let endpoint = components?.url else { throw CallAuthClientError.invalidHomeserver }

        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["matrix_room_id": roomID])

        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw CallAuthClientError.malformedResponse
        }
        guard (200..<300).contains(response.statusCode) else {
            throw CallAuthClientError.rejected(response.statusCode)
        }
        guard data.count <= 16 * 1_024,
              let credentials = try? JSONDecoder().decode(CallAuthCredentials.self, from: data),
              Self.isValidCallID(credentials.callId),
              callID == nil || callID == credentials.callId,
              Self.isSecureLiveKitOrigin(credentials.url),
              credentials.token.utf8.count >= 32,
              credentials.token.utf8.count <= 8_192,
              !credentials.token.contains(where: { $0.isWhitespace }) else {
            throw CallAuthClientError.malformedResponse
        }
        guard credentials.expiresAt > Int64(Date().timeIntervalSince1970) else {
            throw CallAuthClientError.expired
        }
        return credentials
    }

    private static func homeserverOrigin(_ value: String) -> URL? {
        guard let components = URLComponents(string: value),
              let scheme = components.scheme?.lowercased(),
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.path.isEmpty || components.path == "/" else { return nil }
        let isLocalHTTP: Bool
        #if DEBUG
        isLocalHTTP = scheme == "http" && ["localhost", "127.0.0.1"].contains(host.lowercased())
        #else
        isLocalHTTP = false
        #endif
        guard scheme == "https" || isLocalHTTP else { return nil }
        var origin = URLComponents()
        origin.scheme = scheme
        origin.host = host
        origin.port = components.port
        return origin.url
    }

    private static func isSecureLiveKitOrigin(_ value: String) -> Bool {
        guard let components = URLComponents(string: value),
              components.scheme?.lowercased() == "wss",
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.path.isEmpty || components.path == "/" else { return false }
        return true
    }

    private static func isValidCallID(_ value: String) -> Bool {
        let uuid = value.lowercased()
        if UUID(uuidString: value)?.uuidString.lowercased() == uuid, value == uuid { return true }
        let pieces = value.split(separator: ".", omittingEmptySubsequences: false)
        guard pieces.count == 3, pieces[0] == "v1", pieces[1].utf8.count == 86,
              pieces[2].utf8.count == 43 else { return false }
        return pieces[1...].allSatisfy { piece in
            piece.utf8.allSatisfy {
                (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95
            }
        }
    }
}

private final class NoRedirectSessionDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}
