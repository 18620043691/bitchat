import Foundation
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

enum DiagnosticErrorCategory: String {
    case bluetooth = "Bluetooth operation failed"
    case media = "Media operation failed"
    case permission = "Permission was unavailable"
    case update = "Update check or download failed"
    case app = "App operation failed"
}

struct DiagnosticEvent: Equatable {
    let date: Date
    let category: DiagnosticErrorCategory
}

enum DiagnosticEventStore {
    private static let lock = NSLock()
    private static let capacity = 20
    private static var events: [DiagnosticEvent] = []

    static func record(_ category: DiagnosticErrorCategory, date: Date = Date()) {
        lock.lock()
        defer { lock.unlock() }
        events.append(DiagnosticEvent(date: date, category: category))
        if events.count > capacity {
            events.removeFirst(events.count - capacity)
        }
    }

    static func recentEvents() -> [DiagnosticEvent] {
        lock.lock()
        defer { lock.unlock() }
        return events
    }
}

enum DiagnosticDescriptionRedactor {
    private static let patterns = [
        #"(?is)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----"#,
        #"(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*"#,
        #"(?i)\b(?:token|secret|password|api[_-]?key)\s*[:=]\s*[^\s,;]+"#,
        #"\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b"#,
        #"\b[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}\b"#,
        #"\b[0-9a-fA-F]{8,}\b"#,
        #"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"#
    ]

    static func redact(_ description: String) -> String {
        var result = String(description.prefix(2_000))
        for pattern in patterns {
            guard let expression = try? NSRegularExpression(pattern: pattern) else { continue }
            let range = NSRange(result.startIndex..<result.endIndex, in: result)
            result = expression.stringByReplacingMatches(
                in: result,
                range: range,
                withTemplate: "[redacted]"
            )
        }
        return String(result.filter { $0 == "\n" || $0 == "\t" || !$0.isControlCharacter })
    }
}

enum DiagnosticReportBuilder {
    @MainActor
    static func create(
        description: String,
        bundle: Bundle = .main,
        now: Date = Date(),
        recentEvents: [DiagnosticEvent] = DiagnosticEventStore.recentEvents()
    ) throws -> URL {
        let info = bundle.infoDictionary ?? [:]
        let appName = (info["CFBundleDisplayName"] as? String) ?? "bitchat"
        let version = (info["CFBundleShortVersionString"] as? String) ?? "unknown"
        let build = (info["CFBundleVersion"] as? String) ?? "unknown"
        let timestamp = ISO8601DateFormatter().string(from: now)
        let errors = recentEvents.suffix(20).map { event in
            "- \(ISO8601DateFormatter().string(from: event.date)): \(event.category.rawValue)"
        }.joined(separator: "\n")
        let safeDescription = DiagnosticDescriptionRedactor.redact(description)
        #if os(iOS)
        let systemName = UIDevice.current.systemName
        let systemVersion = UIDevice.current.systemVersion
        let model = UIDevice.current.model
        #else
        let systemName = "macOS"
        let systemVersion = ProcessInfo.processInfo.operatingSystemVersionString
        let model = "Mac"
        #endif
        let report = """
        bitchat diagnostic report
        Created: \(timestamp)
        App: \(appName) \(version) (\(build))
        OS: \(systemName) \(systemVersion)
        Device model: \(model)

        User description (common secrets and identifiers redacted):
        \(safeDescription.isEmpty ? "(No description provided.)" : safeDescription)

        Recent categorized errors (raw logs and chat history are excluded):
        \(errors.isEmpty ? "- No recent categorized app errors were recorded." : errors)

        Privacy: This file was created on-device by your request and was not uploaded.
        App chat history, message contents, private keys, tokens and peer identifiers are never read.
        Your description is manually supplied; review the report before sharing.
        """

        guard let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else {
            throw CocoaError(.fileWriteUnknown)
        }
        let directory = cache.appendingPathComponent("feedback", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("bitchat-diagnostics-\(Int(now.timeIntervalSince1970)).txt")
        try Data(report.utf8).write(to: url, options: .atomic)
        return url
    }
}

private extension Character {
    var isControlCharacter: Bool {
        unicodeScalars.allSatisfy(CharacterSet.controlCharacters.contains)
    }
}
