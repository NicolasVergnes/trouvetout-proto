package app.trouvetout.core.swift

import app.trouvetout.core.platform.toByteArray
import platform.Foundation.NSData

/** One copy of the bytes of an `NSData` (a picked photo) into a Kotlin array; Swift has no cheap way to do it. */
public fun bytesOf(data: NSData): ByteArray = data.toByteArray()
