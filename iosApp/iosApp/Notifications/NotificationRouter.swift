import Foundation
import Shared
import UIKit
import UserNotifications

/// The item sheet a tapped reminder opens (E2); ContentView pushes it once the app is unlocked.
@MainActor
final class NotificationRouter: ObservableObject {
    static let shared = NotificationRouter()

    @Published private(set) var itemId: String?

    /// Only a well-formed identifier is kept: the notification's payload is the app's own, but checked anyway.
    func open(itemId: String) {
        guard Ulid.shared.isValid(value: itemId) else { return }
        self.itemId = itemId
    }

    func consume() {
        itemId = nil
    }
}

/// Sets the notification delegate at launch, so that a tap on a reminder that started the app is delivered.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _: UIApplication,
        didFinishLaunchingWithOptions _: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        ReminderScheduler.registerCategory()
        return true
    }

    /// A reminder due while the app is open still shows, as a banner.
    func userNotificationCenter(
        _: UNUserNotificationCenter,
        willPresent _: UNNotification,
        withCompletionHandler completion: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completion([.banner, .list, .sound])
    }

    /// The completion-handler form: the `async` one of this delegate has been known to end on the wrong thread.
    func userNotificationCenter(
        _: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completion: @escaping () -> Void
    ) {
        guard response.notification.request.identifier.hasPrefix(ReminderScheduler.prefix),
              let itemId = response.notification.request.content.userInfo[ReminderScheduler.itemKey] as? String
        else {
            completion()
            return
        }
        Task { @MainActor in
            NotificationRouter.shared.open(itemId: itemId)
            completion()
        }
    }
}
