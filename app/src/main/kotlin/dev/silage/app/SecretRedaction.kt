package dev.silage.app

/** Redact at the last logging boundary; never include raw auth exceptions in UI. */
object SecretRedaction {
    private val query = Regex("""([?&][^=\s"&]+)=([^&\s"<>]+)""")
    private val bearer = Regex("""(?i)(Bearer\s+)[^\s",}]+""")
    private val jsonSecret = Regex("""(?i)("(?:password|access_token|refresh_token|profile_token|authorization|api_key)"\s*:\s*")[^"]*""")
    private val assignment = Regex("""(?i)((?:password|access_token|refresh_token|profile_token|api_key|token|signature|sig|st)=)[^&\s",}]+""")
    fun text(value: String): String {
        var result = query.replace(value, "$1=…")
        result = bearer.replace(result, "$1…")
        result = jsonSecret.replace(result, "$1…")
        return assignment.replace(result, "$1…")
    }
}
