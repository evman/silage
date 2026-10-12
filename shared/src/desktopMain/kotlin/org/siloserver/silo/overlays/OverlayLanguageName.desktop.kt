package org.siloserver.silo.overlays

import java.util.Locale

internal actual fun overlayLanguageName(tag: String): String? {
    val trimmed = tag.trim()
    if (trimmed.isEmpty()) return null
    val locale = Locale.forLanguageTag(trimmed.replace('_', '-'))
    val name = locale.getDisplayName(Locale.ENGLISH)
    // `getDisplayName` echoes the tag back when ICU has no name for it;
    // treat that as "unnamed" and fall back to the uppercased code.
    if (name.isBlank() || name.equals(trimmed, ignoreCase = true)) {
        return trimmed.uppercase(Locale.ROOT)
    }
    return name
}
