package org.siloserver.silo.overlays

/**
 * English display name for a language tag, matching web's `formatLanguage`
 * (English CLDR names, so "en" → "English" not "EN") and the Apple
 * clients' `formatLanguageName`. A tag the platform can't name falls back
 * to the uppercased tag rather than web's "Unknown language (…)" sentence,
 * which doesn't fit a badge.
 */
internal expect fun overlayLanguageName(tag: String): String?
