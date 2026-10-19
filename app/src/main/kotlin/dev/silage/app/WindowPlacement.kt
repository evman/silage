package dev.silage.app

import androidx.compose.ui.window.WindowPlacement

/** Windows fills its monitor with a normal borderless window, avoiding AWT exclusive mode. */
internal fun initialWindowPlacement(osName: String = System.getProperty("os.name")): WindowPlacement =
    if (osName.startsWith("Windows")) WindowPlacement.Floating else WindowPlacement.Fullscreen
