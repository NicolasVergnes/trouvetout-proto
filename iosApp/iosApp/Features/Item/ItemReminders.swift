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
        Task { notificationsDenied = await container.reminders.denied() }
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
        perform(then: afterAdding) { [self] in
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
        perform(then: on ? afterAdding : nil) { [self] in
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
            notificationsDenied = await container.reminders.denied()
        }
    }

    /// The phone's answer may have changed in the Settings app.
    func refreshAuthorization() {
        Task { notificationsDenied = await container.reminders.denied() }
    }

    private func afterAdding() {
        Task {
            if await container.reminders.needsAuthorization() {
                askPermission = true
            }
            notificationsDenied = await container.reminders.denied()
        }
    }

    private func perform(
        then success: (() -> Void)? = nil,
        _ work: @escaping @MainActor () async throws -> CommandResult
    ) {
        busy = true
        failed = false
        Task {
            defer { busy = false }
            let result = try? await work()
            failed = !(result is CommandResultDone)
            if !failed {
                success?()
            }
        }
    }
}

/// E2 / E6 on the item sheet: dated reminders (moments to come only), the six-month check and its « checked ».
struct RemindersSection: View {
    @ObservedObject var model: ItemRemindersModel
    @State private var picking = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Section("reminders_title") {
            if model.failed {
                Text("capture_problem_rejected").font(.callout).foregroundStyle(.red)
            }
            ForEach(model.dated, id: \.id) { reminder in
                HStack {
                    Text(momentText(reminder.fireAt, withTime: true))
                    Spacer()
                    Button("reminder_remove") { model.remove(reminder) }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(
                            String(
                                format: NSLocalizedString("reminder_remove_label", comment: ""),
                                momentText(reminder.fireAt, withTime: true)
                            )
                        )
                }
            }
            Button("reminder_add") { picking = true }
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
        }
        .disabled(model.busy)
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                model.refreshAuthorization()
            }
        }
        .sheet(isPresented: $picking) {
            MomentPickerSheet { date in
                picking = false
                model.add(date)
            }
        }
        .alert("reminder_permission_title", isPresented: $model.askPermission) {
            Button("action_continue") { model.grant() }
            Button("action_not_now", role: .cancel) {}
        } message: {
            Text("reminder_permission_text")
        }
    }

    private func checkText(_ check: Reminder_) -> String {
        let date = Date(timeIntervalSince1970: Double(check.fireAt.toEpochMilliseconds()) / 1000)
        let text = momentText(check.fireAt, withTime: false)
        let key = date <= Date() ? "reminder_check_due" : "reminder_check_next"
        return String(format: NSLocalizedString(key, comment: ""), text)
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
                    Text("reminder_in_past").foregroundStyle(.red)
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
