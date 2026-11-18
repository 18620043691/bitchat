import BitFoundation
import SwiftUI

/// Compact "groups" section for the people sheet: one row per private group
/// this device belongs to, tappable to open the group chat window.
struct GroupChatList: View {
    @ThemedPalette private var palette
    @State private var activeAction: GroupChatActionRequest?
    @State private var feedback: String?

    let groups: [GroupChatRow]
    let onTapGroup: (PeerID) -> Void
    let onPerformAction: (GroupChatActionRequest, String) -> CommandResult

    private enum Strings {
        static let header = String(localized: "groups.section.header", comment: "Section header above the private groups list")
        static let creator = String(localized: "groups.state.creator", comment: "State label for a group the user created")
        static let unread = String(localized: "mesh_peers.state.unread", comment: "State label for a peer with unread private messages")
        static let newMessagesTooltip = String(localized: "mesh_peers.tooltip.new_messages", comment: "Tooltip for the unread messages indicator")
        static let openGroupHint = String(localized: "groups.accessibility.open_group_hint", comment: "Accessibility hint on a group row explaining activation opens the group chat")
        static let memberCountFormat = String(localized: "groups.member_count %@", comment: "Member count shown next to a group name; placeholder is the count")
        static let createGroup = String(localized: "groups.action.create", defaultValue: "Create group", comment: "Button that creates a private group")
        static let empty = String(localized: "groups.empty", defaultValue: "No groups yet", comment: "Empty state for the private groups list")
        static let inviteMember = String(localized: "groups.action.invite", defaultValue: "Invite member", comment: "Menu action to invite someone to a private group")
        static let removeMember = String(localized: "groups.action.remove", defaultValue: "Remove member", comment: "Menu action to remove someone from a private group")
        static let leaveGroup = String(localized: "groups.action.leave", defaultValue: "Leave group", comment: "Menu action to leave a private group")
        static let manageGroup = String(localized: "groups.accessibility.manage", defaultValue: "Manage group", comment: "Accessibility label for a group's management menu")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                PeopleSectionHeader(
                    icon: "person.3.fill",
                    iconColor: palette.primary,
                    title: Strings.header
                )
                Spacer()
                Button {
                    activeAction = GroupChatActionRequest(action: .create, group: nil)
                } label: {
                    Image(systemName: "plus")
                        .font(.bitchatSystem(size: 14, weight: .semibold))
                        .foregroundColor(palette.primary)
                        .frame(width: 36, height: 36)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Strings.createGroup)
            }

            if groups.isEmpty {
                Text(Strings.empty)
                    .bitchatFont(size: 12)
                    .foregroundColor(palette.secondary)
                    .padding(.horizontal)
                    .padding(.vertical, 8)
            }

            ForEach(groups) { group in
                HStack(spacing: 4) {
                    Button {
                        onTapGroup(group.peerID)
                    } label: {
                        HStack(spacing: 4) {
                            hashtagText(for: group)
                                .bitchatFont(size: 14)
                                .foregroundColor(palette.primary)
                                .lineLimit(1)
                                .truncationMode(.tail)

                            Text(String(format: Strings.memberCountFormat, locale: .current, "\(group.memberCount)"))
                                .bitchatFont(size: 12)
                                .foregroundColor(palette.secondary)

                            if group.isCreator {
                                Image(systemName: "crown.fill")
                                    .font(.bitchatSystem(size: 9))
                                    .foregroundColor(.yellow)
                                    .help(Strings.creator)
                            }

                            Spacer()

                            if group.hasUnread {
                                Image(systemName: "envelope.fill")
                                    .font(.bitchatSystem(size: 10))
                                    .foregroundColor(.orange)
                                    .help(Strings.newMessagesTooltip)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(accessibilityDescription(for: group))
                    .accessibilityHint(Strings.openGroupHint)

                    Menu {
                        if group.isCreator {
                            Button {
                                activeAction = GroupChatActionRequest(action: .invite, group: group)
                            } label: {
                                Label(Strings.inviteMember, systemImage: "person.badge.plus")
                            }
                            Button {
                                activeAction = GroupChatActionRequest(action: .remove, group: group)
                            } label: {
                                Label(Strings.removeMember, systemImage: "person.fill.xmark")
                            }
                        }
                        Button(role: .destructive) {
                            activeAction = GroupChatActionRequest(action: .leave, group: group)
                        } label: {
                            Label(Strings.leaveGroup, systemImage: "rectangle.portrait.and.arrow.right")
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.bitchatSystem(size: 14, weight: .semibold))
                            .foregroundColor(palette.secondary)
                            .frame(width: 36, height: 36)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel(Strings.manageGroup)
                }
                .padding(.horizontal)
                .padding(.vertical, 4)
            }

            if let feedback {
                Text(feedback)
                    .bitchatFont(size: 12)
                    .foregroundColor(palette.secondary)
                    .padding(.horizontal)
                    .padding(.vertical, 6)
            }
        }
        .sheet(item: $activeAction) { request in
            GroupChatActionSheet(request: request) { value in
                onPerformAction(request, value)
            } onSuccess: { message in
                feedback = message
            }
        }
    }

    /// "#name", not localized: there is nothing to translate, and a
    /// letterless "#%@" catalog key breaks string catalog symbol generation
    /// (SwiftPM's default build system). It renders as the localized lookup
    /// did: the name is isolated (the lookup isolates names containing
    /// right-to-left text; isolating every name renders the same), so the
    /// "#" stays on the same side of RTL names, and the text is tagged with
    /// the app language, as text from a localization table is.
    private func hashtagText(for group: GroupChatRow) -> Text {
        var hashtag = AttributedString("#\u{2068}\(group.name)\u{2069}")
        hashtag.languageIdentifier = Bundle.main.preferredLocalizations.first ?? "en"
        return Text(hashtag)
    }

    private func accessibilityDescription(for group: GroupChatRow) -> String {
        var parts: [String] = [
            group.name,
            String(format: Strings.memberCountFormat, locale: .current, "\(group.memberCount)")
        ]
        if group.isCreator { parts.append(Strings.creator) }
        if group.hasUnread { parts.append(Strings.unread) }
        return parts.joined(separator: ", ")
    }
}

struct GroupChatActionRequest: Identifiable {
    enum Action: String, Equatable {
        case create
        case invite
        case remove
        case leave
    }

    let action: Action
    let group: GroupChatRow?

    var id: String { "\(action.rawValue)-\(group?.id ?? "new")" }
}

private struct GroupChatActionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ThemedPalette private var palette
    @State private var input = ""
    @State private var errorMessage: String?

    let request: GroupChatActionRequest
    let onSubmit: (String) -> CommandResult
    let onSuccess: (String) -> Void

    private var title: String {
        switch request.action {
        case .create: String(localized: "groups.action.create", defaultValue: "Create group", comment: "Title for the create group form")
        case .invite: String(localized: "groups.action.invite", defaultValue: "Invite member", comment: "Title for the invite member form")
        case .remove: String(localized: "groups.action.remove", defaultValue: "Remove member", comment: "Title for the remove member form")
        case .leave: String(localized: "groups.action.leave", defaultValue: "Leave group", comment: "Title for the leave group confirmation")
        }
    }

    private var placeholder: String {
        switch request.action {
        case .create:
            String(localized: "groups.field.name", defaultValue: "Group name", comment: "Placeholder for the group name field")
        case .invite, .remove:
            String(localized: "groups.field.nickname", defaultValue: "Nearby member nickname", comment: "Placeholder for a group member nickname field")
        case .leave:
            ""
        }
    }

    private var submitTitle: String {
        switch request.action {
        case .create: String(localized: "groups.action.create", defaultValue: "Create group", comment: "Submit button for the create group form")
        case .invite: String(localized: "groups.action.invite", defaultValue: "Invite member", comment: "Submit button for the invite member form")
        case .remove: String(localized: "groups.action.remove", defaultValue: "Remove member", comment: "Submit button for the remove member form")
        case .leave: String(localized: "groups.action.leave", defaultValue: "Leave group", comment: "Submit button for the leave group confirmation")
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                if request.action != .leave {
                    TextField(placeholder, text: $input)
                    #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    #endif
                } else {
                    Text(
                        String(
                            format: String(localized: "groups.confirm.leave", defaultValue: "Leave “%@” and remove it from this device?", comment: "Confirmation message when leaving a private group"),
                            locale: .current,
                            request.group?.name ?? ""
                        )
                    )
                    .foregroundColor(palette.secondary)
                }

                if request.action == .remove {
                    Text(
                        String(
                            localized: "groups.remove.warning",
                            defaultValue: "Removing a member rotates the group key so they cannot read future messages.",
                            comment: "Explains the effect of removing a group member"
                        )
                    )
                    .foregroundColor(palette.secondary)
                }

                if let errorMessage {
                    Text(errorMessage)
                        .foregroundColor(.red)
                }
            }
            .navigationTitle(title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(
                        submitTitle,
                        role: request.action == .leave || request.action == .remove ? .destructive : nil
                    ) {
                        submit()
                    }
                    .disabled(request.action != .leave && input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }

    private func submit() {
        switch onSubmit(input) {
        case .success(let message):
            dismiss()
            if let message { onSuccess(message) }
        case .error(let message):
            errorMessage = message
        case .handled:
            dismiss()
        }
    }
}
