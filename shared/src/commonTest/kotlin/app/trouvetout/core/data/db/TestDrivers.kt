package app.trouvetout.core.data.db

/** An in-memory database for tests, opened with the driver of the platform running the test. */
expect fun inMemoryDriverFactory(): DriverFactory

fun openTestDatabase(): TrouveToutDb = openDatabase(inMemoryDriverFactory().create())
