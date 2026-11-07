import CryptoKit
import Foundation
import Security
import Shared

/// AES-256-GCM of CryptoKit and the system's secure random, for the backups (A9, ADR-025). The format, the key
/// derivation and the rules are in the core.
final class CryptoKitBackupCipher: NSObject, NativeBackupCipher {
    private static let tagBytes = 16

    func randomBytes(count: Int32) -> Data? {
        var bytes = [UInt8](repeating: 0, count: Int(count))
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            return nil
        }
        return Data(bytes)
    }

    func seal(key: Data, nonce: Data, aad: Data, plain: Data) -> Data? {
        guard
            let gcmNonce = try? AES.GCM.Nonce(data: nonce),
            let box = try? AES.GCM.seal(plain, using: SymmetricKey(data: key), nonce: gcmNonce, authenticating: aad)
        else {
            return nil
        }
        return box.ciphertext + box.tag
    }

    func open(key: Data, nonce: Data, aad: Data, sealed: Data) -> Data? {
        guard
            sealed.count >= Self.tagBytes,
            let gcmNonce = try? AES.GCM.Nonce(data: nonce),
            let box = try? AES.GCM.SealedBox(
                nonce: gcmNonce,
                ciphertext: sealed.dropLast(Self.tagBytes),
                tag: sealed.suffix(Self.tagBytes)
            )
        else {
            return nil
        }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }
}
