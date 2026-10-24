package app.trouvetout.core.platform

/**
 * SHA-256 digest of [bytes] computed with the platform's system implementation (no custom crypto).
 * Render it with the stdlib `toHexString()` (lowercase) for storage and model pack verification.
 */
public expect fun sha256(bytes: ByteArray): ByteArray
