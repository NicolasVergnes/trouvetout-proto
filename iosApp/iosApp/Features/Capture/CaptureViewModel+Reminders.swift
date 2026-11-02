import Foundation

/// H9 for the reminders of a sentence (E2): the notification question comes after the first one, never at launch.
extension CaptureViewModel {
    func askNotificationsIfNeeded() {
        Task {
            if await container.reminders.needsAuthorization() {
                askNotifications = true
            }
        }
    }

    func grantNotifications() {
        Task { _ = await container.reminders.requestAuthorization() }
    }
}
