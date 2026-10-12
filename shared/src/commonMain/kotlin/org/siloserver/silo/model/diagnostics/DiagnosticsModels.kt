package org.siloserver.silo.model.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class DiagnosticsManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    val report: DiagnosticsReport,
    val destination: DiagnosticsDestination,
    val consent: DiagnosticsConsent,
    val crash: DiagnosticsCrashInfo? = null,
    @SerialName("device_summary") val deviceSummary: DiagnosticsDeviceSummary,
    @SerialName("playback_session_ids") val playbackSessionIds: List<String>,
    @SerialName("log_summary") val logSummary: DiagnosticsLogSummary,
    val archive: DiagnosticsArchive,
)

@Serializable
data class DiagnosticsReport(
    val type: DiagnosticsReportType,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("capture_session_id") val captureSessionId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("app_build") val appBuild: String,
    val platform: DiagnosticsPlatform,
    @SerialName("os_version") val osVersion: String,
    @SerialName("profile_id") val profileId: String? = null,
)

@Serializable
enum class DiagnosticsReportType {
    @SerialName("crash") CRASH,
    @SerialName("anr") ANR,
    @SerialName("native_crash") NATIVE_CRASH,
    @SerialName("hang") HANG,
    @SerialName("abnormal_exit") ABNORMAL_EXIT,
    @SerialName("manual") MANUAL,
}

@Serializable
enum class DiagnosticsPlatform {
    @SerialName("android") ANDROID,
    @SerialName("android-tv") ANDROID_TV,
    @SerialName("ios") IOS,
    @SerialName("tvos") TVOS,
}

@Serializable
data class DiagnosticsDestination(
    @SerialName("server_instance_id") val serverInstanceId: String,
)

@Serializable
data class DiagnosticsConsent(
    val mode: DiagnosticsConsentMode,
    @SerialName("notice_version") val noticeVersion: Int,
)

@Serializable
enum class DiagnosticsConsentMode {
    @SerialName("prompt") PROMPT,
    @SerialName("always") ALWAYS,
    @SerialName("manual") MANUAL,
}

@Serializable
data class DiagnosticsCrashInfo(
    val summary: String,
    @SerialName("stack_excerpt") val stackExcerpt: String? = null,
    val thread: String? = null,
    val foreground: Boolean? = null,
    val source: DiagnosticsCrashSource,
    val provenance: DiagnosticsCrashProvenance,
    @SerialName("occurred_at") val occurredAt: String,
)

@Serializable
enum class DiagnosticsCrashSource {
    @SerialName("ueh") UEH,
    @SerialName("exit_info") EXIT_INFO,
    @SerialName("metrickit") METRICKIT,
    @SerialName("exit_sentinel") EXIT_SENTINEL,
}

@Serializable
enum class DiagnosticsCrashProvenance {
    @SerialName("pre_failure") PRE_FAILURE,
    @SerialName("post_restart") POST_RESTART,
    @SerialName("metric_reporting_period") METRIC_REPORTING_PERIOD,
}

@Serializable
data class DiagnosticsDeviceSummary(
    val manufacturer: String,
    val model: String,
    val os: String,
    @SerialName("form_factor") val formFactor: String,
)

@Serializable
data class DiagnosticsLogSummary(
    val lines: Long,
    @SerialName("bytes_gz") val bytesGzip: Long,
    @SerialName("dropped_lines") val droppedLines: Long,
    val categories: List<DiagnosticsLogCategory>,
    @SerialName("debug_logging") val debugLogging: Boolean,
)

@Serializable
enum class DiagnosticsLogCategory {
    @SerialName("playback") PLAYBACK,
    @SerialName("focus") FOCUS,
    @SerialName("network") NETWORK,
    @SerialName("lifecycle") LIFECYCLE,
    @SerialName("browse") BROWSE,
    @SerialName("cast") CAST,
    @SerialName("download") DOWNLOAD,
    @SerialName("crash") CRASH,
    @SerialName("other") OTHER,
}

@Serializable
data class DiagnosticsArchive(
    val entries: List<String>,
    val bytes: Long,
    @SerialName("uncompressed_bytes") val uncompressedBytes: Long,
    val sha256: String,
) {
    companion object {
        val ALLOWED_ENTRIES: Set<String> = linkedSetOf(
            "manifest.json",
            "device.json",
            "logs.jsonl",
            "crash/summary.json",
            "crash/stack.txt",
            "crash/tombstone.pb",
            "crash/metrickit.json",
            "breadcrumbs.jsonl",
        )
    }
}

@Serializable
data class DiagnosticsStatusResponse(
    val status: DiagnosticsAvailabilityStatus,
    @SerialName("server_instance_id") val serverInstanceId: String,
    @SerialName("accepted_schema_versions") val acceptedSchemaVersions: List<Int>,
    @SerialName("max_bundle_bytes") val maxBundleBytes: Long,
    @SerialName("max_manifest_bytes") val maxManifestBytes: Long,
    @SerialName("retention_days") val retentionDays: Int,
    @SerialName("consent_notice_version") val consentNoticeVersion: Int,
)

@Serializable
enum class DiagnosticsAvailabilityStatus {
    @SerialName("available") AVAILABLE,
    @SerialName("disabled") DISABLED,
    @SerialName("storage_unavailable") STORAGE_UNAVAILABLE,
}

@Serializable
data class DiagnosticsUploadResponse(
    @SerialName("report_id") val reportId: String,
    @SerialName("short_id") val shortId: String,
)

@Serializable
data class DiagnosticsDeviceSnapshot(
    @SerialName("captured_at") val capturedAt: String,
    val provenance: DiagnosticsDeviceProvenance,
    val identity: JsonElement,
    val display: JsonElement,
    val audio: JsonElement,
    @SerialName("video_codecs") val videoCodecs: JsonElement,
    val network: JsonElement,
)

typealias DeviceSnapshot = DiagnosticsDeviceSnapshot

@Serializable
enum class DiagnosticsDeviceProvenance {
    @SerialName("pre_failure") PRE_FAILURE,
    @SerialName("post_restart") POST_RESTART,
}

enum class DiagnosticsDeviceSentinel(val wireValue: String) {
    UNKNOWN("unknown"),
    NOT_COLLECTED("not_collected"),
}

@Serializable
data class DiagnosticsLogLine(
    @SerialName("ts") val timestamp: String,
    val run: String,
    @SerialName("lvl") val level: DiagnosticsLogLevel,
    @SerialName("cat") val category: DiagnosticsLogCategory,
    val tag: String,
    @SerialName("msg") val message: String,
    @SerialName("attrs") val attributes: Map<String, JsonElement>? = null,
)

@Serializable
enum class DiagnosticsLogLevel {
    @SerialName("V") VERBOSE,
    @SerialName("D") DEBUG,
    @SerialName("I") INFO,
    @SerialName("W") WARNING,
    @SerialName("E") ERROR,
}
