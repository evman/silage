package org.siloserver.silo.model.download

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRecordSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `DownloadRecord round-trips full server shape`() {
        val source = """
            {
              "id": "dl_abc123",
              "content_id": "tt0816692",
              "episode_id": "tt6470762",
              "batch_id": "batch_xyz",
              "media_file_id": 2390488,
              "file_size": 5368709120,
              "bytes_sent": 1342177280,
              "kind": "queued",
              "status": "downloading",
              "created_at": "2026-05-24T01:02:03Z",
              "completed_at": null
            }
        """.trimIndent()

        val r = json.decodeFromString<DownloadRecord>(source)
        assertEquals("dl_abc123", r.id)
        assertEquals("tt0816692", r.contentId)
        assertEquals("tt6470762", r.episodeId)
        assertEquals("batch_xyz", r.batchId)
        assertEquals(2390488, r.mediaFileId)
        assertEquals(5_368_709_120L, r.fileSize)
        assertEquals(1_342_177_280L, r.bytesSent)
        assertEquals(DownloadKind.Queued, r.kindEnum())
        assertEquals(DownloadStatus.Downloading, r.statusEnum())
        assertEquals("2026-05-24T01:02:03Z", r.createdAt)
        assertNull(r.completedAt)
    }

    @Test
    fun `DownloadRecord decodes when optional fields are absent`() {
        val source = """
            {
              "id": "dl_xyz",
              "content_id": "tt12345",
              "media_file_id": 1,
              "file_size": 1024,
              "bytes_sent": 0,
              "kind": "queued",
              "status": "queued",
              "created_at": "2026-05-24T00:00:00Z"
            }
        """.trimIndent()

        val r = json.decodeFromString<DownloadRecord>(source)
        assertNull(r.episodeId)
        assertNull(r.batchId)
        assertNull(r.completedAt)
        assertEquals(DownloadStatus.Queued, r.statusEnum())
    }

    @Test
    fun `DownloadRecord decodes the transcode quality fields and preparing status`() {
        // A just-requested bitrate transcode: status `preparing`, quality the
        // client asked for, and the delivery contract the server will produce.
        val source = """
            {
              "id": "dl_transcode",
              "content_id": "mv_1",
              "media_file_id": 7,
              "file_size": 100,
              "bytes_sent": 0,
              "kind": "queued",
              "status": "preparing",
              "quality": "5mbps",
              "effective_quality": "5mbps",
              "delivery_format": "transcode",
              "target_bitrate_kbps": 5000,
              "created_at": "2026-05-24T00:00:00Z"
            }
        """.trimIndent()

        val r = json.decodeFromString<DownloadRecord>(source)
        assertEquals(DownloadStatus.Preparing, r.statusEnum())
        assertEquals("5mbps", r.quality)
        assertEquals("5mbps", r.effectiveQuality)
        assertEquals("transcode", r.deliveryFormat)
        assertEquals(5000, r.targetBitrateKbps)
    }

    @Test
    fun `DownloadStatus maps preparing and ready lifecycle states`() {
        assertEquals(DownloadStatus.Preparing, DownloadStatus.fromWire("preparing"))
        assertEquals(DownloadStatus.Ready, DownloadStatus.fromWire("ready"))
        // Quality fields are optional — a plain original row omits them.
        assertNull(
            json.decodeFromString<DownloadRecord>(
                """
                {
                  "id": "dl_orig",
                  "content_id": "mv_2",
                  "media_file_id": 8,
                  "file_size": 1,
                  "bytes_sent": 0,
                  "kind": "queued",
                  "status": "ready",
                  "created_at": "2026-05-24T00:00:00Z"
                }
                """.trimIndent()
            ).quality
        )
    }

    @Test
    fun `DownloadStatus fromWire is lenient on unknown values`() {
        // A newer server adds a status the client doesn't know about — the
        // record must still decode, mapping to Unknown for downstream
        // ignore/dead-letter handling.
        val source = """
            {
              "id": "dl_future",
              "content_id": "tt1",
              "media_file_id": 1,
              "file_size": 1,
              "bytes_sent": 0,
              "kind": "queued",
              "status": "paused_by_quota",
              "created_at": "2026-05-24T00:00:00Z"
            }
        """.trimIndent()

        val r = json.decodeFromString<DownloadRecord>(source)
        assertEquals(DownloadStatus.Unknown, r.statusEnum())
        assertEquals("paused_by_quota", r.status)
    }

    @Test
    fun `DownloadRequest encodes original download quality without bitrate`() {
        val encoded = json.encodeToString(
            DownloadRequest(
                contentId = "movie-1",
                fileId = 10,
                quality = DownloadQuality.Original.wire,
                targetBitrateKbps = DownloadQuality.Original.targetBitrateKbps,
            )
        )

        assertTrue(encoded.contains(""""quality":"original""""))
        assertFalse(encoded.contains("target_bitrate_kbps"))
    }

    @Test
    fun `DownloadRequest encodes bitrate preset quality with target bitrate`() {
        val preset = DownloadQuality.Mbps10
        val encoded = json.encodeToString(
            DownloadRequest(
                contentId = "movie-1",
                fileId = 10,
                quality = preset.wire,
                targetBitrateKbps = preset.targetBitrateKbps,
            )
        )

        assertTrue(encoded.contains(""""quality":"10mbps""""))
        assertTrue(encoded.contains(""""target_bitrate_kbps":10000"""))
    }

    @Test
    fun `DownloadQuality parses supported presets and rejects unknowns to original`() {
        assertEquals(DownloadQuality.Original, DownloadQuality.fromWire("original"))
        assertEquals(DownloadQuality.Mbps20, DownloadQuality.fromWire("20mbps"))
        assertEquals(DownloadQuality.Mbps10, DownloadQuality.fromWire("10mbps"))
        assertEquals(DownloadQuality.Mbps5, DownloadQuality.fromWire("5mbps"))
        assertEquals(DownloadQuality.Mbps2, DownloadQuality.fromWire("2mbps"))
        assertEquals(DownloadQuality.Mbps1, DownloadQuality.fromWire("1mbps"))
        assertEquals(DownloadQuality.Original, DownloadQuality.fromWire("1080p"))
        assertEquals(DownloadQuality.Original, DownloadQuality.fromWire(null))
    }
}
