import Foundation
import Security
import Shared

/// The Keychain item that holds the database key (H2, ADR-023): a generic password readable after the first unlock,
/// on this device only, never synchronised to iCloud. The core generates the key and decides when to use it.
final class KeychainDatabaseKey: NSObject, KeychainItem {
    private let service: String
    private let account: String

    init(service: String = "app.trouvetout.proto.database", account: String = "database-key") {
        self.service = service
        self.account = account
    }

    private var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
        ]
    }

    /// « No item » only for `errSecItemNotFound`: any other status (locked before the first unlock, missing
    /// entitlement) is a failure, which the core reports as an unavailable store, never as a lost key.
    func read() -> KeychainRead {
        var lookup = query
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            return KeychainRead(data: result as? Data, failed: result as? Data == nil)
        case errSecItemNotFound:
            return KeychainRead(data: nil, failed: false)
        default:
            return KeychainRead(data: nil, failed: true)
        }
    }

    /// Adds the key; an existing item is never replaced (a duplicate is a failure, the core then stops).
    func write(data: Data) -> Bool {
        var item = query
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    func remove() {
        SecItemDelete(query as CFDictionary)
    }

    /// Tests: whether the item is there.
    var stored: Data? {
        read().data
    }
}
