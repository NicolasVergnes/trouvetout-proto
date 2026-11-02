import Foundation
import Shared
import UserNotifications

/// The part of `UNUserNotificationCenter` the reminders use; tests replace it.
protocol NotificationCenterClient: AnyObject {
    func pendingIdentifiers() async -> [String]
    /// Identifiers of the notifications on screen, with whether each one names its item.
    func delivered() async -> [(identifier: String, named: Bool)]
    func add(_ request: UNNotificationRequest) async throws
    func removePending(_ identifiers: [String])
    func removeDelivered(_ identifiers: [String])
    func removeAll()
    func status() async -> UNAuthorizationStatus
    func requestAuthorization() async -> Bool
}

final class SystemNotificationCenter: NotificationCenterClient {
    private let center = UNUserNotificationCenter.current()

    func pendingIdentifiers() async -> [String] {
        await center.pendingNotificationRequests().map(\.identifier)
    }

    func delivered() async -> [(identifier: String, named: Bool)] {
        await center.deliveredNotifications().map { notification in
            let info = notification.request.content.userInfo
            return (notification.request.identifier, info[ReminderScheduler.namedKey] as? Bool ?? true)
        }
    }

    func add(_ request: UNNotificationRequest) async throws {
        try await center.add(request)
    }

    func removePending(_ identifiers: [String]) {
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
    }

    func removeDelivered(_ identifiers: [String]) {
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    func removeAll() {
        center.removeAllPendingNotificationRequests()
        center.removeAllDeliveredNotifications()
    }

    func status() async -> UNAuthorizationStatus {
        await center.notificationSettings().authorizationStatus
    }

    func requestAuthorization() async -> Bool {
        do {
            return try await center.requestAuthorization(options: [.alert, .sound])
        } catch {
            return false
        }
    }
}

/// Hands the reminders to the system (E2, E6, H4; N6: nothing runs in the background). iOS runs no code when a
/// notification fires, so its text is written when it is scheduled, and replaced at each change of the schedule
/// (rename, « sensitive », bin). The text names the item at most, never its place; with previews hidden (locked
/// phone, by default) only « Rappel TrouveTout » shows. At most 50 pending: iOS keeps 64 per app.
@MainActor
final class ReminderScheduler {
    nonisolated static let categoryIdentifier = "reminder"
    nonisolated static let prefix = "reminder."
    nonisolated static let itemKey = "itemId"
    nonisolated static let namedKey = "named"
    /// `ReminderSchedule.MAX_SCHEDULED` of the core: iOS keeps 64 pending notifications per app.
    static let maxPending: Int32 = 50

    private let center: NotificationCenterClient
    private var queue: Task<Void, Never>?

    init(center: NotificationCenterClient = SystemNotificationCenter()) {
        self.center = center
    }

    /// The category whose placeholder replaces the text when previews are hidden; set once at launch.
    static func registerCategory() {
        let category = UNNotificationCategory(
            identifier: categoryIdentifier,
            actions: [],
            intentIdentifiers: [],
            hiddenPreviewsBodyPlaceholder: NSLocalizedString("notif_generic", comment: ""),
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    /// Queues a reconciliation: each one runs after the previous, never two at once.
    func submit(_ schedule: ReminderSchedule) {
        let previous = queue
        queue = Task { [weak self] in
            await previous?.value
            await self?.reconcile(schedule)
        }
    }

    func reconcile(_ schedule: ReminderSchedule, now: Date = Date()) async {
        let nowMillis = Int64(now.timeIntervalSince1970 * 1000)
        let upcoming = schedule.upcoming(nowMillis: nowMillis, limit: Self.maxPending)
        let wanted = Set(upcoming.map { Self.prefix + $0.id })
        let stale = await center.pendingIdentifiers().filter { $0.hasPrefix(Self.prefix) && !wanted.contains($0) }
        center.removePending(stale)
        for reminder in upcoming {
            if let request = Self.request(for: reminder, now: now) {
                try? await center.add(request)
            }
        }
        let withdrawn = await center.delivered().filter { shown in
            guard shown.identifier.hasPrefix(Self.prefix) else { return false }
            let reminderId = String(shown.identifier.dropFirst(Self.prefix.count))
            return schedule.withdraws(reminderId: reminderId, shownNamed: shown.named)
        }
        center.removeDelivered(withdrawn.map(\.identifier))
    }

    /// H6: every pending and delivered notification goes with the data.
    func clear() {
        queue?.cancel()
        queue = nil
        center.removeAll()
    }

    /// Whether the owner still has to answer the system question (asked after the first reminder, H9).
    func needsAuthorization() async -> Bool {
        await center.status() == .notDetermined
    }

    func requestAuthorization() async -> Bool {
        await center.requestAuthorization()
    }

    func denied() async -> Bool {
        await center.status() == .denied
    }

    /// The request of one reminder, at its absolute instant (a time zone change does not move it).
    static func request(for reminder: ScheduledReminder, now: Date) -> UNNotificationRequest? {
        let interval = Double(reminder.fireAtMillis) / 1000 - now.timeIntervalSince1970
        guard interval > 0 else { return nil }
        let content = UNMutableNotificationContent()
        content.body = text(of: reminder)
        content.sound = .default
        content.categoryIdentifier = categoryIdentifier
        content.threadIdentifier = reminder.itemId
        content.userInfo = [itemKey: reminder.itemId, namedKey: reminder.title != nil]
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
        return UNNotificationRequest(identifier: prefix + reminder.id, content: content, trigger: trigger)
    }

    /// « Rappel : passeport », « Vérifier : passeport », or the generic text for a sensitive item (title nil).
    static func text(of reminder: ScheduledReminder) -> String {
        guard let title = reminder.title else { return NSLocalizedString("notif_generic", comment: "") }
        let key = reminder.kind == .check ? "notif_check" : "notif_reminder"
        return String(format: NSLocalizedString(key, comment: ""), title)
    }
}
