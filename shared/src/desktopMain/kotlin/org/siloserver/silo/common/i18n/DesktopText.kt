package org.siloserver.silo.common.i18n

import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong
import org.w3c.dom.Element

/**
 * Desktop resolver for [UiText]: the desktop counterpart of android-shared's
 * `UiText.text()`. Silage ships in English, so catalog text comes from the
 * English `strings_catalog.xml` that scripts/vendor-shared.sh copies from
 * silo-android (a generated Android resource file, read here by name).
 */
fun UiText.text(): String = when (this) {
    is UiText.Raw -> text
    is UiText.Key -> DesktopCatalog.format(key.key, args)
    is UiText.Plural -> DesktopCatalog.formatPlural(key.key, key.countIndex, count, args)
    is UiText.Res -> "" // Android resource ids never reach desktop code.
    // As upstream's English test resolver (EnglishText.android.kt) formats them.
    is UiText.Number ->
        if (maxFractionDigits == 0) {
            String.format(Locale.US, "%0${minIntegerDigits.coerceAtLeast(1)}d", value.roundToLong())
        } else {
            NumberFormat.getNumberInstance(Locale.US).apply {
                isGroupingUsed = false
                maximumFractionDigits = this@text.maxFractionDigits
                roundingMode = RoundingMode.HALF_UP
            }.format(value)
        }
    is UiText.CalendarDate -> LocalDate.of(year, month, day).format(DateTimeFormatter.ofPattern("MMM d, y", Locale.US))
    is UiText.LanguageName -> {
        val name = Locale.forLanguageTag(tag.replace('_', '-')).getDisplayName(Locale.ENGLISH)
        when {
            name.isBlank() -> if (uppercaseIfUnnamed) tag.uppercase(Locale.ROOT) else tag
            uppercaseIfUnnamed && name.equals(tag, ignoreCase = true) -> tag.uppercase(Locale.ROOT)
            else -> name
        }
    }
}

/** English catalog text by catalog key ("home.section.continueWatching"). */
object DesktopCatalog {
    private const val RESOURCE = "/org/siloserver/silo/i18n/strings_catalog.xml"

    private class Catalog(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private val catalog: Catalog by lazy { load() }

    private fun load(): Catalog {
        val stream = DesktopCatalog::class.java.getResourceAsStream(RESOURCE) ?: return Catalog(emptyMap(), emptyMap())
        val doc = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            when (el.tagName) {
                "string" -> strings[name] = unescape(el.textContent)
                "plurals" -> {
                    val items = el.getElementsByTagName("item")
                    plurals[name] = (0 until items.length).associate { j ->
                        val item = items.item(j) as Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                }
            }
        }
        return Catalog(strings, plurals)
    }

    /** Android resource escapes: \' \" \n \t \\ \@ \?, and surrounding quotes. */
    internal fun unescape(raw: String): String {
        val s = raw.trim().let { if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it }
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'u' -> if (i + 6 <= s.length) {
                        out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun resName(key: String) = key.replace('.', '_')

    /** The raw string for [key], or the key itself when the catalog lacks it. */
    fun string(key: String): String = catalog.strings[resName(key)] ?: key

    fun format(key: String, args: List<Any>): String {
        val template = string(key)
        return if (args.isEmpty()) template else formatTemplate(template, args.map(::resolveArg))
    }

    fun formatPlural(key: String, countIndex: Int, count: Int, args: List<Any>): String {
        val forms = catalog.plurals[resName(key)] ?: return key
        val template = (if (count == 1) forms["one"] else null) ?: forms["other"] ?: forms.values.firstOrNull() ?: return key
        val values = args.map(::resolveArg).toMutableList<Any>().apply { add(countIndex.coerceAtMost(size), count) }
        return formatTemplate(template, values)
    }

    private fun resolveArg(arg: Any): Any = if (arg is UiText) arg.text() else arg

    private fun formatTemplate(template: String, args: List<Any>): String =
        runCatching { String.format(Locale.US, template, *args.toTypedArray()) }.getOrDefault(template)
}
