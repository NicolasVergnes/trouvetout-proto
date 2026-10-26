package app.trouvetout.core.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** Dispatchers are injected (tests use unconfined ones); nothing in `shared` references `Dispatchers.Main` directly. */
public class CoroutineDispatchers(
    public val io: CoroutineDispatcher = Dispatchers.IO,
    public val default: CoroutineDispatcher = Dispatchers.Default,
    public val main: CoroutineDispatcher = Dispatchers.Main,
)
