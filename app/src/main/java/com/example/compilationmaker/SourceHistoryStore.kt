package com.example.compilationmaker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

data class SourceHistoryEntry(
    val sourceUri: String,
    val outputPath: String,
    val outputUri: String,
    val displayName: String,
    val completedAtMs: Long,
    val sourceDeleted: Boolean = false
)

/**
 * Tracks successful compilation pairs (source → output) for auto-delete and batch cleanup.
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
                sourceDeleted = false
            )
        )
        // Cap history so prefs stay small.
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
                    sourceDeleted = o.optBoolean("sourceDeleted", false)
                ).takeIf { it.sourceUri.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** Entries whose source still appears readable and not yet deleted. */
    fun loadDeletableSources(): List<SourceHistoryEntry> = load().filter { entry ->
        !entry.sourceDeleted && sourceStillExists(entry.sourceUri)
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
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "source_history"
        private const val KEY = "entries_json"
        private const val MAX_ENTRIES = 200
    }
}
