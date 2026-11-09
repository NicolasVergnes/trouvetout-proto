import Combine
import Foundation
import Shared
import SwiftUI

/// The reminders of one item (E2, E6): every change is a command of the core.
@MainActor
final class ItemRemindersModel: ObservableObject {
    @Published private(set) var reminders: [Reminder_] = []
    @Published private(set) var busy = false
    @Published private(set) var failed = false
    /// H9: the explanation shown before the system question, after the first reminder.
    @Published var askPermission = false
    @Published private(set) var notificationsDenied = false
    /// « Not now », or reminders brought by an import: the question is still open and nothing will ring.
    @Published private(set) var notificationsNotAsked = false
    @Published var picking = false

    let itemId: String
    private let container: AppContainer
    private let flows = FlowSubscriptions()
    private var readiness: AnyCancellable?

    init(container: AppContainer, itemId: String) {
        self.container = container
        self.itemId = itemId
        readiness = container.$ready.sink { [weak self] ready in
            self?.observe(ready ? container.shared : nil)
        }
    }

    private func observe(_ shared: SharedFactory?) {
        flows.cancelAll()
        reminders = []
        guard let shared else { return }
        let flow = shared.reminders.observeForItem(itemId: itemId)
        flows.watch(flow, scope: shared.uiScope) { [weak self] (list: NSArray) in
            self?.reminders = (list as? [Reminder_]) ?? []
        }
        refreshAuthorization()
    }

    var dated: [Reminder_] {
        reminders.filter { $0.kind == .date }
    }

    /// E6: the pending check, if « vérifier tous les 6 mois » is on.
    var check: Reminder_? {
        reminders
            .filter { $0.kind == .check }
            .min { $0.fireAt.toEpochMilliseconds() < $1.fireAt.toEpochMilliseconds() }
    }

    func add(_ date: Date) {
        let millis = Int64(date.timeIntervalSince1970 * 1000)
        perform(askingNotifications: true) { [self] in
            let at = InstantsKt.instantOf(epochMillis: millis)
            let commands = container.shared.attachmentCommands
            return try await commands.setReminder(
                itemId: itemId,
                fireAt: at,
                kind: .date,
                source: .text,
                rawInput: nil
            )
        }
    }

    func remove(_ reminder: Reminder_) {
        perform { [self] in
            try await container.shared.attachmentCommands.completeReminder(reminderId: reminder.id, source: .text)
        }
    }

    func setChecking(_ on: Bool) {
        perform(askingNotifications: on) { [self] in
            try await container.shared.attachmentCommands.setChecking(itemId: itemId, on: on, source: .text)
        }
    }

    func confirmChecked() {
        perform { [self] in
            try await container.shared.attachmentCommands.confirmChecked(itemId: itemId, source: .text)
        }
    }

    func grant() {
        Task {
            _ = await container.reminders.requestAuthorization()
            container.refreshReminders()
            await readAuthorization()
        }
    }

    /// The phone's answer may have changed in the Settings app.
    func refreshAuthorization() {
        Task { await readAuthorization() }
    }

    private func readAuthorization() async {
        notificationsDenied = await container.reminders.denied()
        notificationsNotAsked = await container.reminders.needsAuthorization()
    }

    private func afterAdding() {
        Task {
            if await container.reminders.needsAuthorization() {
                askPermission = true
            }
            await readAuthorization()
        }
    }

    private func perform(
        askingNotifications: Bool = false,
        _ work: @escaping @MainActor () async throws -> CommandResult
    ) {
        busy = true
        failed = false
        Task {
            defer { busy = false }
            let result = try? await work()
            failed = !(result is CommandResultDone)
            if !failed, askingNotifications {
                afterAdding()
            }
        }
    }
}

/// E2 / E6 on the item sheet: dated reminders (past ones marked), the six-month check and its « checked ».
struct RemindersSection: View {
    @ObservedObject var model: ItemRemindersModel

    var body: some View {
        Section("reminders_title") {
            if model.failed {
                Text("capture_problem_rejected").font(.callout).foregroundStyle(Color.errorText)
            }
            ForEach(model.dated, id: \.id) { reminder in
                HStack {
                    Text(momentText(reminder.fireAt, withTime: true))
                    Spacer()
                    Button("reminder_remove") { model.remove(reminder) }
                        .buttonStyle(.borderless)
                        .frame(minHeight: 44)
                        .accessibilityLabel(
                            String(
                                format: NSLocalizedString("reminder_remove_label", comment: ""),
                                momentText(reminder.fireAt, withTime: true)
                            )
                        )
                }
            }
            Button("reminder_add") { model.picking = true }
            Toggle(isOn: Binding(get: { model.check != nil }, set: { model.setChecking($0) })) {
                VStack(alignment: .leading) {
                    Text("reminder_check_switch")
                    Text("reminder_check_hint").font(.footnote).foregroundStyle(.secondary)
                }
            }
            if let check = model.check {
                Text(checkText(check)).font(.footnote)
                Button("reminder_checked") { model.confirmChecked() }
            }
            if model.notificationsDenied, !model.reminders.isEmpty {
                Text("reminder_notifications_off").font(.footnote).foregroundStyle(.secondary)
                Button("reminder_open_settings") { CaptureTexts.openAppSettings() }
            }
            if model.notificationsNotAsked, !model.reminders.isEmpty {
                Text("reminder_notifications_not_asked").font(.footnote).foregroundStyle(.secondary)
                Button("reminder_notifications_allow") { model.grant() }
            }
        }
        .disabled(model.busy)
    }

    private func checkText(_ check: Reminder_) -> String {
        let date = Date(timeIntervalSince1970: Double(check.fireAt.toEpochMilliseconds()) / 1000)
        let text = momentText(check.fireAt, withTime: false)
        let key = date <= Date() ? "reminder_check_due" : "reminder_check_next"
        return String(format: NSLocalizedString(key, comment: ""), text)
    }
}

/// The picker and the notification question of [RemindersSection], presented from the form: a `Form` repeats a
/// section's modifiers on its lazy rows.
struct RemindersPresentation: ViewModifier {
    @ObservedObject var model: ItemRemindersModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var picked: Date?

    func body(content: Content) -> some View {
        content
            .onChange(of: scenePhase) { phase in
                if phase == .active {
                    model.refreshAuthorization()
                }
            }
            .sheet(
                isPresented: $model.picking,
                onDismiss: addPicked,
                content: {
                    MomentPickerSheet { date in
                        picked = date
                        model.picking = false
                    }
                }
            )
            .alert("reminder_permission_title", isPresented: $model.askPermission) {
                Button("action_continue") { model.grant() }
                Button("action_not_now", role: .cancel) {}
            } message: {
                Text("reminder_permission_text")
            }
    }

    /// Once the sheet is gone: the question that follows the first reminder would be lost during its dismissal.
    private func addPicked() {
        guard let date = picked else { return }
        picked = nil
        model.add(date)
    }
}

/// « jeudi 12 mars 2027 à 09:00 », marked past when it is.
func momentText(_ instant: KotlinInstant, withTime: Bool) -> String {
    let date = Date(timeIntervalSince1970: Double(instant.toEpochMilliseconds()) / 1000)
    let text = date.formatted(date: .complete, time: withTime ? .shortened : .omitted)
    guard withTime, date <= Date() else { return text }
    return String(format: NSLocalizedString("reminder_past", comment: ""), text)
}

/// A day and a time to come.
private struct MomentPickerSheet: View {
    let onPick: (Date) -> Void
    @Environment(\.dismiss) private var dismiss
    /// Tomorrow at 9:00, the default time of a reminder.
    @State private var date: Date = {
        let tomorrow = Date().addingTimeInterval(86400)
        return Calendar.current.date(bySettingHour: 9, minute: 0, second: 0, of: tomorrow) ?? tomorrow
    }()

    var body: some View {
        NavigationStack {
            Form {
                DatePicker(
                    "reminder_time_title",
                    selection: $date,
                    in: Date()...,
                    displayedComponents: [.date, .hourAndMinute]
                )
                .datePickerStyle(.graphical)
                if date <= Date() {
                    Text("reminder_in_past").foregroundStyle(Color.errorText)
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action_cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("action_save") { onPick(date) }
                        .disabled(date <= Date())
                }
            }
        }
    }
}
