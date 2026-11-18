import CryptoKit
import Foundation

struct ReleaseUpdateManifest: Decodable, Equatable {
    struct AndroidRelease: Decodable, Equatable {
        let apkUrl: URL
        let sha256: String
    }

    struct IOSRelease: Decodable, Equatable {
        let testFlightUrl: URL
        let appStoreUrl: URL
    }

    let schemaVersion: Int
    let version: String
    let versionCode: Int
    let releaseNotes: String
    let android: AndroidRelease
    let ios: IOSRelease
}

enum ReleaseUpdateError: LocalizedError {
    case notConfigured
    case invalidResponse
    case untrustedSignature
    case unsupportedManifest

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            "Release checks are not configured for this build."
        case .invalidResponse:
            "The release service returned invalid information."
        case .untrustedSignature:
            "The release information could not be verified."
        case .unsupportedManifest:
            "This release information is not supported."
        }
    }
}

enum ReleaseUpdateService {
    private struct Envelope: Decodable {
        let payload: String
        let signature: String
    }

    static func checkForUpdate(
        bundle: Bundle = .main,
        session: URLSession = .shared
    ) async throws -> ReleaseUpdateManifest? {
        guard let endpointText = bundle.object(forInfoDictionaryKey: "BitchatUpdateManifestURL") as? String,
              let endpoint = URL(string: endpointText),
              endpoint.scheme?.lowercased() == "https",
              endpoint.host != nil,
              let publicKeyText = bundle.object(forInfoDictionaryKey: "BitchatUpdateSigningPublicKey") as? String,
              let publicKeyData = decodeBase64URL(publicKeyText),
              let publicKey = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKeyData) else {
            throw ReleaseUpdateError.notConfigured
        }

        var request = URLRequest(url: endpoint)
        request.timeoutInterval = 10
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse,
              (200..<300).contains(http.statusCode),
              response.url?.scheme?.lowercased() == "https" else {
            throw ReleaseUpdateError.invalidResponse
        }
        var data = Data()
        for try await byte in bytes {
            guard data.count < 128 * 1024 else { throw ReleaseUpdateError.invalidResponse }
            data.append(byte)
        }
        guard let envelope = try? JSONDecoder().decode(Envelope.self, from: data),
              let payload = decodeBase64URL(envelope.payload),
              let signature = decodeBase64URL(envelope.signature),
              signature.count == 64,
              publicKey.isValidSignature(signature, for: payload) else {
            throw ReleaseUpdateError.untrustedSignature
        }
        guard let manifest = try? JSONDecoder().decode(ReleaseUpdateManifest.self, from: payload),
              manifest.schemaVersion == 1,
              !manifest.version.isEmpty,
              manifest.version.count <= 64,
              manifest.version.range(of: "^\\d+(?:\\.\\d+){1,3}$", options: .regularExpression) != nil,
              manifest.versionCode > 0,
              manifest.releaseNotes.count <= 8_000,
              isHTTPS(manifest.android.apkUrl),
              isHTTPS(manifest.ios.testFlightUrl),
              isHTTPS(manifest.ios.appStoreUrl),
              manifest.android.sha256.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil else {
            throw ReleaseUpdateError.unsupportedManifest
        }
        let installedVersion = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        return isNewerVersion(manifest.version, than: installedVersion) ? manifest : nil
    }

    static func verifySignature(payload: Data, signature: Data, publicKey: Data) -> Bool {
        guard signature.count == 64,
              let key = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey) else {
            return false
        }
        return key.isValidSignature(signature, for: payload)
    }

    static func isNewerVersion(_ candidate: String, than installed: String) -> Bool {
        func components(_ value: String) -> [Int]? {
            guard value.range(of: "^\\d+(?:\\.\\d+){1,3}$", options: .regularExpression) != nil else { return nil }
            return value.split(separator: ".").compactMap { Int($0) }
        }
        guard let candidateParts = components(candidate),
              let installedParts = components(installed) else { return false }
        let count = max(candidateParts.count, installedParts.count)
        for index in 0..<count {
            let candidatePart = index < candidateParts.count ? candidateParts[index] : 0
            let installedPart = index < installedParts.count ? installedParts[index] : 0
            if candidatePart != installedPart { return candidatePart > installedPart }
        }
        return false
    }

    private static func isHTTPS(_ url: URL) -> Bool {
        url.scheme?.lowercased() == "https" && url.host != nil
    }

    private static func decodeBase64URL(_ value: String) -> Data? {
        guard value.utf8.allSatisfy({
            ($0 >= UInt8(ascii: "A") && $0 <= UInt8(ascii: "Z"))
                || ($0 >= UInt8(ascii: "a") && $0 <= UInt8(ascii: "z"))
                || ($0 >= UInt8(ascii: "0") && $0 <= UInt8(ascii: "9"))
                || $0 == UInt8(ascii: "-")
                || $0 == UInt8(ascii: "_")
        }) else {
            return nil
        }
        var base64 = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let padding = (4 - base64.count % 4) % 4
        base64.append(String(repeating: "=", count: padding))
        return Data(base64Encoded: base64)
    }
}
