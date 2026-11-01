import LocalAuthentication

/// The device owner's authentication (H3, H4): Face ID or Touch ID when enrolled, the device passcode otherwise. The
/// app has no password of its own: nothing to forget, nothing to recover without a server.
enum DeviceOwner {
    /// A passcode is set: without one there is nothing to ask, and the app lock cannot be turned on.
    static var available: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    /// Shows the system prompt; true once the owner is recognised.
    static func authenticate(reason: String) async -> Bool {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else { return false }
        return await (try? context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }
}
