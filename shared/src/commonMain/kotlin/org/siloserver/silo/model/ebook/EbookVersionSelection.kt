package org.siloserver.silo.model.ebook

import org.siloserver.silo.model.book.BookFormat
import org.siloserver.silo.model.catalog.FileVersion

private val supportedEbookExtensions = setOf(
    "epub",
    "pdf",
    "txt",
    "md",
    "markdown",
    "mobi",
    "azw",
    "azw3",
    "fb2",
    "fbz",
    "cbz",
    "cbr",
)

private val preferredOrder = listOf("epub", "pdf", "cbz", "txt", "md", "markdown", "fb2", "fbz", "cbr", "mobi", "azw3", "azw")

enum class EbookReadMode {
    InApp,
    ExternalOnly,
    Unsupported,
}

data class EbookFormatSupport(
    val key: String?,
    val readMode: EbookReadMode,
    val displayName: String,
    val reason: String,
) {
    val canReadInApp: Boolean get() = readMode == EbookReadMode.InApp
    val canDownloadOriginal: Boolean get() = readMode != EbookReadMode.Unsupported
}

data class ReaderVersionTarget(
    val version: FileVersion,
    val support: EbookFormatSupport,
    val format: BookFormat,
)

fun ebookFormatKey(container: String?, fileName: String?): String? {
    val containerKey = container
        ?.trim()
        ?.lowercase()
        ?.removePrefix(".")
        ?.takeIf { it in supportedEbookExtensions }
        ?.normalizedEbookFormatKey()
    if (containerKey != null) return containerKey

    val name = fileName?.lowercase().orEmpty()
    if (name.endsWith(".fb2.zip")) return "fbz"
    return name.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { it in supportedEbookExtensions }
        ?.normalizedEbookFormatKey()
}

fun FileVersion.ebookFormatKey(): String? =
    ebookFormatKey(container = container, fileName = fileName)

fun FileVersion.isSupportedEbookVersion(): Boolean = ebookFormatKey() != null

fun ebookFormatSupport(formatKey: String?): EbookFormatSupport {
    val key = formatKey
        ?.trim()
        ?.lowercase()
        ?.removePrefix(".")
        ?.takeIf { it.isNotBlank() }
    val normalizedKey = key?.normalizedEbookFormatKey()
    val displayName = normalizedKey.orEmpty().ebookFormatDisplayName()
    return when (normalizedKey) {
        "epub", "pdf", "cbz", "txt", "md", "fb2", "fbz" -> EbookFormatSupport(
            key = normalizedKey,
            readMode = EbookReadMode.InApp,
            displayName = displayName,
            reason = "$displayName can be read in Silo.",
        )
        "cbr", "mobi", "azw", "azw3" -> EbookFormatSupport(
            key = normalizedKey,
            readMode = EbookReadMode.ExternalOnly,
            displayName = displayName,
            reason = "$displayName downloads stay in their original format and can be opened with another reader.",
        )
        else -> EbookFormatSupport(
            key = normalizedKey,
            readMode = EbookReadMode.Unsupported,
            displayName = displayName,
            reason = "This file format is not supported as an ebook download.",
        )
    }
}

private fun String.normalizedEbookFormatKey(): String =
    if (this == "markdown") "md" else this

fun FileVersion.ebookFormatSupport(): EbookFormatSupport =
    ebookFormatSupport(ebookFormatKey())

fun String.ebookFormatDisplayName(): String {
    val key = trim().lowercase().removePrefix(".")
    return when (key) {
        "epub" -> "EPUB"
        "pdf" -> "PDF"
        "cbz" -> "Comic Book ZIP"
        "cbr" -> "Comic Book RAR"
        "fb2", "fbz" -> "FictionBook"
        "mobi" -> "MOBI"
        "azw", "azw3" -> "Kindle"
        "md" -> "Markdown"
        "txt" -> "Plain text"
        "", "unknown" -> "Unknown"
        else -> key.uppercase()
    }
}

fun String.isInAppReadableEbookFormat(): Boolean =
    ebookFormatSupport(this).canReadInApp

fun FileVersion.ebookFormatDisplayName(): String =
    ebookFormatKey().orEmpty().ebookFormatDisplayName()

fun FileVersion.isInAppReadableEbookVersion(): Boolean =
    ebookFormatKey().orEmpty().isInAppReadableEbookFormat()

/** True for mobi/azw/azw3 — the formats the server can convert to EPUB. */
fun FileVersion.isKindleConvertibleEbookVersion(): Boolean =
    ebookFormatKey() in kindleConvertibleFormats

/**
 * In-app readability that accounts for server-side Kindle->EPUB conversion:
 * when [kindleConversionAvailable], a mobi/azw/azw3 file reads in-app (the
 * server serves it as EPUB). Use this for the detail "Read" affordance so the
 * entry point and the reader agree.
 */
fun FileVersion.isInAppReadableEbookVersion(kindleConversionAvailable: Boolean): Boolean =
    isInAppReadableEbookVersion() || (kindleConversionAvailable && isKindleConvertibleEbookVersion())

fun FileVersion.bookFormatFromEbookVersion(): BookFormat {
    val formatFromKey = BookFormat.fromWire(ebookFormatKey())
    return if (formatFromKey != BookFormat.Unknown) {
        formatFromKey
    } else {
        BookFormat.fromPath(fileName)
    }
}

fun ebookPageNumberFromProgressLocation(location: String?): Int? =
    location
        ?.trim()
        ?.takeIf { it.startsWith("page:") }
        ?.removePrefix("page:")
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }

fun ebookProgressPercentForPage(
    page: Int,
    pageCount: Int?,
    currentProgress: Double,
): Double {
    if (pageCount == null || pageCount <= 0) {
        return currentProgress.coerceIn(0.0, 1.0)
    }
    if (pageCount == 1) {
        return if (page <= 0) 0.0 else 1.0
    }
    return (page.coerceAtLeast(0).toDouble() / (pageCount - 1).toDouble()).coerceIn(0.0, 1.0)
}

fun chooseEbookVersion(
    versions: List<FileVersion>,
    requestedFileId: Int?,
): FileVersion? {
    if (requestedFileId != null) {
        versions.firstOrNull { it.fileId == requestedFileId && it.isInAppReadableEbookVersion() }?.let { return it }
    }

    val supported = versions.filter { it.isInAppReadableEbookVersion() }
    if (supported.isEmpty()) return null

    return supported.minBy { version ->
        preferredOrder.indexOf(version.ebookFormatKey()).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }
}

fun chooseReaderVersion(
    versions: List<FileVersion>,
    requestedFileId: Int?,
): ReaderVersionTarget? {
    val targets = versions.mapNotNull { it.readerTargetOrNull() }
    if (requestedFileId != null) {
        targets.firstOrNull { it.version.fileId == requestedFileId }?.let { return it }
    }
    return targets.preferredReaderTarget(EbookReadMode.InApp)
        ?: targets.preferredReaderTarget(EbookReadMode.ExternalOnly)
}

private fun FileVersion.readerTargetOrNull(): ReaderVersionTarget? {
    val support = ebookFormatSupport()
    if (support.readMode == EbookReadMode.Unsupported) return null

    return ReaderVersionTarget(
        version = this,
        support = support,
        format = bookFormatFromEbookVersion(),
    )
}

/** Kindle-family formats the server can convert to EPUB on the fly. */
private val kindleConvertibleFormats = setOf("mobi", "azw", "azw3")

/** True when this target is a Kindle-family file currently routed external-only
 *  — i.e. a candidate for server-side EPUB conversion. Use it to gate the
 *  capability probe so non-Kindle reads don't fetch it. */
fun ReaderVersionTarget.isKindleConvertibleExternal(): Boolean =
    support.readMode == EbookReadMode.ExternalOnly && support.key in kindleConvertibleFormats

/**
 * When the server advertises Kindle->EPUB conversion ([available]), promote a
 * Kindle-family [ReaderVersionTarget] from external-only to in-app: the read
 * endpoint returns EPUB bytes, so the effective render [format] becomes
 * [BookFormat.Epub] and it flows through the normal reflowable reader. The
 * underlying file id is unchanged (the server converts that file). No-op for
 * non-Kindle targets, already-in-app targets, or when conversion is off.
 */
fun ReaderVersionTarget.promotedForKindleConversion(available: Boolean): ReaderVersionTarget {
    if (!available) return this
    val key = support.key ?: return this
    if (key !in kindleConvertibleFormats || support.readMode != EbookReadMode.ExternalOnly) {
        return this
    }
    return copy(
        support = support.copy(
            readMode = EbookReadMode.InApp,
            reason = "${support.displayName} is converted to EPUB by the server and read in Silo.",
        ),
        format = BookFormat.Epub,
    )
}

private fun List<ReaderVersionTarget>.preferredReaderTarget(readMode: EbookReadMode): ReaderVersionTarget? =
    filter { it.support.readMode == readMode }
        .minByOrNull { target ->
            preferredOrder.indexOf(target.support.key).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
