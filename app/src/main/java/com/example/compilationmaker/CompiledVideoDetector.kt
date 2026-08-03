package com.example.compilationmaker

import android.net.Uri

data class MediaVideoEntry(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val durationMs: Long
)

data class CompiledVideoMatch(
    val original: MediaVideoEntry,
    val compiled: MediaVideoEntry,
    val confidence: Float
)

internal fun detectCompiledVideoMatches(videos: List<MediaVideoEntry>): List<CompiledVideoMatch> {
    val compiled = videos.filter { it.relativePath.contains("CompilationMaker", ignoreCase = true) }
    val originals = videos.filterNot { it.relativePath.contains("CompilationMaker", ignoreCase = true) }
    return compiled.mapNotNull { output ->
        val outputStem = output.displayName.substringBeforeLast('.', output.displayName)
            .removePrefix("CompilationMaker_")
            .removePrefix("compilation_")
            .replace(Regex("_\\d{8,}$"), "")
        originals
            .filter { original ->
                val originalStem = original.displayName.substringBeforeLast('.', original.displayName)
                originalStem.equals(outputStem, ignoreCase = true) ||
                    outputStem.contains(originalStem, ignoreCase = true) ||
                    originalStem.contains(outputStem, ignoreCase = true)
            }
            .minByOrNull { kotlin.math.abs(it.durationMs - output.durationMs) }
            ?.let { original ->
                val durationDelta = kotlin.math.abs(original.durationMs - output.durationMs)
                CompiledVideoMatch(original, output, if (durationDelta <= 2_000L) 1f else 0.75f)
            }
    }.distinctBy { it.original.uri to it.compiled.uri }
}

internal fun compiledOriginalNameMatch(originalName: String, compiledName: String): Boolean {
    val originalStem = originalName.substringBeforeLast('.', originalName)
    val outputStem = compiledName.substringBeforeLast('.', compiledName)
        .removePrefix("CompilationMaker_")
        .removePrefix("compilation_")
        .replace(Regex("_\\d{8,}$"), "")
    return originalStem.equals(outputStem, ignoreCase = true) ||
        outputStem.contains(originalStem, ignoreCase = true) ||
        originalStem.contains(outputStem, ignoreCase = true)
}
