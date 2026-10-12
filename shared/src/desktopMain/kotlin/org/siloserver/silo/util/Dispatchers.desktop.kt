package org.siloserver.silo.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

/**
 * Upstream view models launch UI-state updates here. Compose Desktop reads
 * StateFlows from any thread, so Default is correct; :app swaps in
 * Dispatchers.Main (Swing) via Dispatchers.setMain only in tests if needed.
 */
actual val mainDispatcher: CoroutineDispatcher = Dispatchers.Default
