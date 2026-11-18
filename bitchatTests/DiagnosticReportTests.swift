import CryptoKit
import Foundation
import Testing
@testable import bitchat

struct DiagnosticReportTests {
    @Test
    func descriptionRedactsTokensKeysAndIdentifiers() {
        let input = "token=secret123 peer 01234567-89ab-cdef-0123-456789abcdef " +
            "-----BEGIN PRIVATE KEY-----hidden-----END PRIVATE KEY-----"

        let safe = DiagnosticDescriptionRedactor.redact(input)

        #expect(!safe.contains("secret123"))
        #expect(!safe.contains("01234567-89ab"))
        #expect(!safe.contains("hidden"))
        #expect(safe.contains("[redacted]"))
    }

    @Test
    func signedReleaseVerificationRejectsTamperedPayload() throws {
        let privateKey = Curve25519.Signing.PrivateKey()
        let payload = Data(#"{"version":"2.0.0"}"#.utf8)
        let signature = try privateKey.signature(for: payload)

        #expect(ReleaseUpdateService.verifySignature(
            payload: payload,
            signature: signature,
            publicKey: privateKey.publicKey.rawRepresentation
        ))
        #expect(!ReleaseUpdateService.verifySignature(
            payload: payload + Data([0x20]),
            signature: signature,
            publicKey: privateKey.publicKey.rawRepresentation
        ))
    }

    @Test
    func releaseVersionComparisonUsesNumericComponents() {
        #expect(ReleaseUpdateService.isNewerVersion("1.10.0", than: "1.9.9"))
        #expect(ReleaseUpdateService.isNewerVersion("2.0", than: "1.99.99"))
        #expect(!ReleaseUpdateService.isNewerVersion("1.8.0", than: "1.8"))
    }

    @Test
    func recentDiagnosticEventsAreBoundedAndCategoryOnly() {
        for offset in 0..<30 {
            DiagnosticEventStore.record(.bluetooth, date: Date(timeIntervalSince1970: TimeInterval(offset)))
        }

        let events = DiagnosticEventStore.recentEvents()
        #expect(events.count <= 20)
        #expect(events.allSatisfy { $0.category == .bluetooth })
    }
}
