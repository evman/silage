package dev.silage.app

import androidx.compose.ui.window.WindowPlacement

/** AWT exclusive fullscreen on Windows minimizes whenever another app gets focus. */
internal fun initialWindowPlacement(osName: String = System.getProperty("os.name")): WindowPlacement =
    if (osName.startsWith("Windows")) WindowPlacement.Maximized else WindowPlacement.Fullscreen
