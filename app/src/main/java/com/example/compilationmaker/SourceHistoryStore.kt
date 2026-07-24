package com.example.compilationmaker

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

data class SourceHistoryEntry(
    val sourceUri: String,
    val outputPath: String,
    val outputUri: String,
    val displayName: String,
    val completedAtMs: Long,
    val sourceDeleted: Boolean = false,
    /** true when entry came from MediaStore Downloads scan, not exact history. */
    val heuristicMatch: Boolean = false
)

data class CleanupScanSummary(
    val entries: List<SourceHistoryEntry>,
    val compilationMakerExportCount: Int,
    val downloadsVideoCount: Int,
    val historyMatchCount: Int
)

/**
 * Tracks successful compilation pairs (source → output) for auto-delete and batch cleanup.
 * Also discovers candidates by scanning Movies/CompilationMaker + Downloads via MediaStore.
 */
class SourceHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun recordSuccess(
        sourceUri: String,
        outputPath: String,
        outputUri: String,
        displayName: String = ""
    ) {
        if (sourceUri.isBlank()) return
        val entries = load().toMutableList()
        entries.removeAll { it.sourceUri == sourceUri && it.outputPath == outputPath }
        entries.add(
            0,
            SourceHistoryEntry(
                sourceUri = sourceUri,
                outputPath = outputPath,
                outputUri = outputUri,
                displayName = displayName.ifBlank { guessDisplayName(sourceUri) },
                completedAtMs = System.currentTimeMillis(),
                sourceDeleted = false,
                heuristicMatch = false
            )
        )
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        save(entries)
    }

    @Synchronized
    fun markSourceDeleted(sourceUri: String) {
        val entries = load().map {
            if (it.sourceUri == sourceUri) it.copy(sourceDeleted = true) else it
        }
        save(entries)
    }

    @Synchronized
    fun load(): List<SourceHistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SourceHistoryEntry(
                    sourceUri = o.optString("sourceUri", ""),
                    outputPath = o.optString("outputPath", ""),
                    outputUri = o.optString("outputUri", ""),
                    displayName = o.optString("displayName", ""),
                    completedAtMs = o.optLong("completedAtMs", 0L),
                    sourceDeleted = o.optBoolean("sourceDeleted", false),
                    heuristicMatch = o.optBoolean("heuristicMatch", false)
                ).takeIf { it.sourceUri.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** Entries whose source still appears readable and not yet deleted. */
    fun loadDeletableSources(): List<SourceHistoryEntry> = load().filter { entry ->
        !entry.sourceDeleted && sourceStillExists(entry.sourceUri)
    }

    /**
     * Device scan used by CleanupActivity:
     * 1) exact SourceHistory pairs still on device
     * 2) last successful job source (if still present)
     * 3) if Movies/CompilationMaker has exports, list remaining Downloads videos as candidates
     */
    fun discoverDeletableSources(): CleanupScanSummary {
        val history = loadDeletableSources()
        val seen = history.map { it.sourceUri }.toMutableSet()
        val merged = history.toMutableList()

        // Last successful job source (covers runs before history was wired).
        runCatching {
            val last = CompilationJobStore(appContext).loadLastSuccess() ?: return@runCatching
            val src = last.sourceUri
            if (src.isNotBlank() && src !in seen && sourceStillExists(src)) {
                seen += src
                merged += SourceHistoryEntry(
                    sourceUri = src,
                    outputPath = last.outputPath,
                    outputUri = last.outputUri,
                    displayName = resolveDisplayName(src),
                    completedAtMs = last.completedAtMs.takeIf { it > 0L } ?: last.updatedAtMs,
                    sourceDeleted = false,
                    heuristicMatch = false
                )
            }
        }

        val cmExports = queryVideosUnderRelativePath(COMPILATION_MAKER_RELATIVE_PATH)
        val downloads = queryVideosUnderRelativePath(DOWNLOAD_RELATIVE_PATH) +
            queryVideosUnderRelativePath(DOWNLOADS_RELATIVE_PATH)

        // When CM exports exist, surface Downloads videos not already listed so the user can
        // delete originals that predate history tracking.
        if (cmExports.isNotEmpty()) {
            downloads.forEach { video ->
                val uri = video.uriString
                if (uri in seen) return@forEach
                // Skip files that themselves live under CompilationMaker (already exports).
                if (video.relativePath.contains("CompilationMaker", ignoreCase = true)) return@forEach
                seen += uri
                merged += SourceHistoryEntry(
                    sourceUri = uri,
                    outputPath = "",
                    outputUri = "",
                    displayName = video.displayName.ifBlank { uri.takeLast(48) },
                    completedAtMs = video.dateAddedMs,
                    sourceDeleted = false,
                    heuristicMatch = true
                )
            }
        }

        return CleanupScanSummary(
            entries = merged,
            compilationMakerExportCount = cmExports.size,
            downloadsVideoCount = downloads.size,
            historyMatchCount = history.size
        )
    }

    fun sourceStillExists(sourceUri: String): Boolean {
        val uri = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return false
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> cursor.moveToFirst() } == true
        }.getOrDefault(false)
    }

    fun tryDeleteSource(sourceUri: String): Boolean {
        val uri = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return false
        val deleted = runCatching {
            if (DocumentsContract.isDocumentUri(appContext, uri)) {
                DocumentsContract.deleteDocument(appContext.contentResolver, uri)
            } else {
                appContext.contentResolver.delete(uri, null, null) > 0
            }
        }.getOrDefault(false)
        if (deleted) markSourceDeleted(sourceUri)
        return deleted
    }

    fun resolveDisplayName(sourceUri: String): String {
        val uri = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return sourceUri.takeLast(40)
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: guessDisplayName(sourceUri)
    }

    private data class MediaVideo(
        val uriString: String,
        val displayName: String,
        val relativePath: String,
        val dateAddedMs: Long
    )

    private fun queryVideosUnderRelativePath(relativePathPrefix: String): List<MediaVideo> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED
        )
        // RELATIVE_PATH is like "Movies/CompilationMaker/" or "Download/"
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%$relativePathPrefix%")
        return runCatching {
            appContext.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                val addedIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val out = ArrayList<MediaVideo>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val uri = Uri.withAppendedPath(collection, id.toString()).toString()
                    out += MediaVideo(
                        uriString = uri,
                        displayName = cursor.getString(nameIdx).orEmpty(),
                        relativePath = cursor.getString(pathIdx).orEmpty(),
                        dateAddedMs = cursor.getLong(addedIdx) * 1000L
                    )
                }
                out
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun guessDisplayName(sourceUri: String): String {
        val path = Uri.parse(sourceUri).lastPathSegment.orEmpty()
        return path.ifBlank { sourceUri.takeLast(48) }
    }

    private fun save(entries: List<SourceHistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("sourceUri", e.sourceUri)
                    put("outputPath", e.outputPath)
                    put("outputUri", e.outputUri)
                    put("displayName", e.displayName)
                    put("completedAtMs", e.completedAtMs)
                    put("sourceDeleted", e.sourceDeleted)
                    put("heuristicMatch", e.heuristicMatch)
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "source_history"
        private const val KEY = "entries_json"
        private const val MAX_ENTRIES = 200
        private const val COMPILATION_MAKER_RELATIVE_PATH = "Movies/CompilationMaker"
        private const val DOWNLOAD_RELATIVE_PATH = "Download"
        private const val DOWNLOADS_RELATIVE_PATH = "Downloads"
    }
}
