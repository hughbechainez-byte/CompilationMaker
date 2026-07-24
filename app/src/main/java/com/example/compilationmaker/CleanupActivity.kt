package com.example.compilationmaker

import android.os.Bundle
import android.view.View
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
 * Launched as a secondary launcher entry so MainActivity does not need a full rewrite.
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
            text = "Loading…"
            setPadding(0, 16, 0, 16)
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
            text = "Refresh"
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
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { store.loadDeletableSources() }
            statusText.text = if (entries.isEmpty()) {
                "No original videos found that still exist and have a recorded compilation."
            } else {
                "${entries.size} original(s) with a CompilationMaker export. Select which to delete."
            }
            val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            entries.forEach { entry ->
                val name = entry.displayName.ifBlank {
                    withContext(Dispatchers.IO) { store.resolveDisplayName(entry.sourceUri) }
                }
                val row = CheckBox(this@CleanupActivity).apply {
                    text = "$name\nExported ${df.format(Date(entry.completedAtMs))}\n${entry.sourceUri.takeLast(64)}"
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
        AlertDialog.Builder(this)
            .setTitle("Delete ${selected.size} original(s)?")
            .setMessage(
                "This permanently deletes the original source video(s). " +
                    "Compilation exports are kept. This cannot be undone."
            )
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
