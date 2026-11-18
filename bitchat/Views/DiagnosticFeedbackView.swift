import SwiftUI

struct DiagnosticFeedbackView: View {
    @ThemedPalette private var palette
    @State private var description = ""
    @State private var reportURL: URL?
    @State private var errorText: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Create a diagnostic report on this device. Nothing is uploaded. Chat history and raw system logs are excluded.")
                .bitchatFont(size: 12)
                .foregroundColor(palette.secondary)
                .fixedSize(horizontal: false, vertical: true)

            TextEditor(text: $description)
                .frame(minHeight: 76, maxHeight: 120)
                .padding(6)
                .background(palette.background.opacity(0.55), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(alignment: .topLeading) {
                    if description.isEmpty {
                        Text("Describe what happened. Avoid chat text, names, IDs, or secrets.")
                            .bitchatFont(size: 12)
                            .foregroundColor(palette.secondary.opacity(0.7))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 14)
                            .allowsHitTesting(false)
                    }
                }
                .accessibilityLabel("Problem description")
                .onChange(of: description) { newValue in
                    if newValue.count > 2_000 {
                        description = String(newValue.prefix(2_000))
                    }
                    reportURL = nil
                }

            if let errorText {
                Text(errorText)
                    .bitchatFont(size: 11)
                    .foregroundColor(palette.alertRed)
            }

            HStack(spacing: 12) {
                Button {
                    do {
                        reportURL = try DiagnosticReportBuilder.create(description: description)
                        errorText = nil
                    } catch {
                        errorText = "Could not create the report."
                    }
                } label: {
                    Label("Create report", systemImage: "doc.text")
                        .bitchatFont(size: 12, weight: .semibold)
                }
                .buttonStyle(.plain)
                .foregroundColor(palette.accent)

                if let reportURL {
                    ShareLink(item: reportURL) {
                        Label("Share / Save", systemImage: "square.and.arrow.up")
                            .bitchatFont(size: 12, weight: .semibold)
                    }
                    .foregroundColor(palette.accent)
                }
            }

            Text("Review the file before sharing. Fixes are delivered in later TestFlight or App Store releases; reports do not change the app remotely.")
                .bitchatFont(size: 11)
                .foregroundColor(palette.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct ReleaseUpdatesView: View {
    @ThemedPalette private var palette
    @Environment(\.openURL) private var openURL
    @State private var isChecking = false
    @State private var statusText = "Updates are checked only when you choose."
    @State private var release: ReleaseUpdateManifest?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(statusText)
                .bitchatFont(size: 12)
                .foregroundColor(palette.secondary)
                .fixedSize(horizontal: false, vertical: true)

            Button {
                Task { await checkForUpdate() }
            } label: {
                Label(isChecking ? "Checking…" : "Check for updates", systemImage: "arrow.clockwise")
                    .bitchatFont(size: 12, weight: .semibold)
            }
            .buttonStyle(.plain)
            .foregroundColor(palette.accent)
            .disabled(isChecking)

            if let release {
                Text("Version \(release.version)")
                    .bitchatFont(size: 13, weight: .semibold)
                    .foregroundColor(palette.primary)
                if !release.releaseNotes.isEmpty {
                    Text(release.releaseNotes)
                        .bitchatFont(size: 12)
                        .foregroundColor(palette.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Link(destination: release.ios.testFlightUrl) {
                    Label("Open TestFlight", systemImage: "airplane")
                        .bitchatFont(size: 12, weight: .semibold)
                }
                .foregroundColor(palette.accent)
                Link(destination: release.ios.appStoreUrl) {
                    Label("Open App Store", systemImage: "arrow.up.right.square")
                        .bitchatFont(size: 12, weight: .semibold)
                }
                .foregroundColor(palette.accent)
            }
        }
    }

    @MainActor
    private func checkForUpdate() async {
        isChecking = true
        defer { isChecking = false }
        do {
            release = try await ReleaseUpdateService.checkForUpdate()
            statusText = release == nil ? "This version is up to date." : "A verified release is available."
        } catch {
            DiagnosticEventStore.record(.update)
            statusText = error.localizedDescription
        }
    }
}
