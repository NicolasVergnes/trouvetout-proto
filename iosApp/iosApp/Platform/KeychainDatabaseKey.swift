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

    func read() -> Data? {
        var lookup = query
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(lookup as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    func write(data: Data) -> Bool {
        var item = query
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(item as CFDictionary, nil)
        if added == errSecDuplicateItem {
            let update: [String: Any] = [
                kSecValueData as String: data,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ]
            return SecItemUpdate(query as CFDictionary, update as CFDictionary) == errSecSuccess
        }
        return added == errSecSuccess
    }

    func remove() {
        SecItemDelete(query as CFDictionary)
    }
}
