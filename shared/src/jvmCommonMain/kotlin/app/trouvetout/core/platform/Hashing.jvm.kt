package app.trouvetout.core.platform

import java.security.MessageDigest

public actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
