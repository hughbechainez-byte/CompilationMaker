package com.example.compilationmaker

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Batch cleanup of original videos that already have a CompilationMaker export.
 * Scans Movies/CompilationMaker + Downloads via MediaStore in addition to history.
 */
class CleanupActivity : AppCompatActivity() {

    private lateinit var store: SourceHistoryStore
    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private val checkBoxes = ArrayList<Pair<CheckBox, SourceHistoryEntry>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SourceHistoryStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val title = TextView(this).apply {
            text = "Clean up original videos"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        statusText = TextView(this).apply {
            text = "Scanning Movies/CompilationMaker and Downloads…"
            setPadding(0, 16, 0, 16)
            textSize = 13f
        }
        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)

        val selectAll = Button(this).apply {
            text = "Select all"
            setOnClickListener { checkBoxes.forEach { it.first.isChecked = true } }
        }
        val clearAll = Button(this).apply {
            text = "Clear selection"
            setOnClickListener { checkBoxes.forEach { it.first.isChecked = false } }
        }
        val refresh = Button(this).apply {
            text = "Rescan device"
            setOnClickListener { reload() }
        }
        val deleteBtn = Button(this).apply {
            text = "Delete selected originals"
            setOnClickListener { confirmDelete() }
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(selectAll)
        root.addView(clearAll)
        root.addView(refresh)
        root.addView(deleteBtn)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(root)
        reload()
    }

    private fun reload() {
        listContainer.removeAllViews()
        checkBoxes.clear()
        statusText.text = "Scanning Movies/CompilationMaker and Downloads…"
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) { store.discoverDeletableSources() }
            val entries = summary.entries
            statusText.text = buildString {
                append("CM exports in Movies/CompilationMaker: ${summary.compilationMakerExportCount}\n")
                append("Videos in Downloads: ${summary.downloadsVideoCount}\n")
                append("Exact history matches: ${summary.historyMatchCount}\n")
                if (entries.isEmpty()) {
                    append("\nNo originals found to clean up.")
                    if (summary.compilationMakerExportCount == 0) {
                        append(" Save a compilation first (Movies/CompilationMaker), then rescan.")
                    } else if (summary.downloadsVideoCount == 0) {
                        append(" No videos left in Downloads.")
                    }
                } else {
                    append("\n${entries.size} candidate original(s). Select which to delete.")
                    if (entries.any { it.heuristicMatch }) {
                        append(" Entries marked [Downloads] are not exact history pairs — confirm before deleting.")
                    }
                }
            }
            val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            entries.forEach { entry ->
                val name = entry.displayName.ifBlank {
                    withContext(Dispatchers.IO) { store.resolveDisplayName(entry.sourceUri) }
                }
                val tag = if (entry.heuristicMatch) "[Downloads] " else "[History] "
                val whenLabel = if (entry.completedAtMs > 0L) {
                    df.format(Date(entry.completedAtMs))
                } else {
                    "unknown date"
                }
                val row = CheckBox(this@CleanupActivity).apply {
                    text = "$tag$name\n$whenLabel\n${entry.sourceUri.takeLast(72)}"
                    textSize = 13f
                    setPadding(0, 12, 0, 12)
                }
                checkBoxes += row to entry
                listContainer.addView(row)
            }
        }
    }

    private fun confirmDelete() {
        val selected = checkBoxes.filter { it.first.isChecked }.map { it.second }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select at least one original", Toast.LENGTH_SHORT).show()
            return
        }
        val heuristic = selected.count { it.heuristicMatch }
        val message = buildString {
            append("This permanently deletes ${selected.size} original source video(s). ")
            append("Compilation exports in Movies/CompilationMaker are kept. This cannot be undone.")
            if (heuristic > 0) {
                append("\n\n$heuristic of the selected item(s) came from a Downloads scan (not exact history). Double-check before deleting.")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Delete ${selected.size} original(s)?")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ -> performDelete(selected) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDelete(selected: List<SourceHistoryEntry>) {
        lifecycleScope.launch {
            statusText.text = "Deleting…"
            val result = withContext(Dispatchers.IO) {
                var ok = 0
                var fail = 0
                selected.forEach { entry ->
                    if (store.tryDeleteSource(entry.sourceUri)) ok++ else fail++
                }
                ok to fail
            }
            Toast.makeText(
                this@CleanupActivity,
                "Deleted ${result.first}, failed ${result.second}",
                Toast.LENGTH_LONG
            ).show()
            reload()
        }
    }
}
