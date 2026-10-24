package app.trouvetout.core.platform

import java.nio.file.Files

actual fun testTempDirectory(): String = Files.createTempDirectory("trouvetout-test").toFile().apply {
    deleteOnExit()
}.absolutePath
