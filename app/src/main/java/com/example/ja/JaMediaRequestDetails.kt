package com.example.ja

import androidx.compose.foundation.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Displays the full transient request data captured for the active player session. */
@Composable
internal fun JaMediaRequestDetailsDialog(
    candidate: JaMediaSourceCandidate,
    onDismiss: () -> Unit
) {
    val details = buildString {
        appendLine("Type: ${candidate.kind}")
        appendLine("URL: ${candidate.url}")
        if (candidate.pageUrl.isNotBlank()) appendLine("Page: ${candidate.pageUrl}")
        if (candidate.frameId >= 0) appendLine("Frame ID: ${candidate.frameId}")
        appendLine("Referer: ${candidate.referer.ifBlank { "(not supplied)" }}")
        appendLine("Request headers:")
        if (candidate.requestHeaders.isEmpty()) appendLine("(none captured)")
        candidate.requestHeaders.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
            appendLine("$name: $value")
        }
        if (candidate.browserFinalUrl.isNotBlank() || candidate.browserStatus != null || candidate.browserMime.isNotBlank()) {
            appendLine()
            appendLine("Browser response:")
            appendLine("Final URL: ${candidate.browserFinalUrl.ifBlank { "(not available)" }}")
            appendLine("HTTP status: ${candidate.browserStatus?.toString() ?: "(not available)"}")
            appendLine("MIME type: ${candidate.browserMime.ifBlank { "(not available)" }}")
        }
        if (candidate.responseUrl.isNotBlank() || candidate.responseHeaders.isNotEmpty() ||
            candidate.bytesLoaded != null || candidate.loadError.isNotBlank()) {
            appendLine()
            appendLine("Response / Exo load:")
            appendLine("Final URL: ${candidate.responseUrl.ifBlank { "(not available)" }}")
            appendLine("HTTP status: ${candidate.responseStatus?.toString() ?: "(not available)"}")
            appendLine("Bytes loaded: ${candidate.bytesLoaded?.toString() ?: "(not available)"}")
            appendLine("Load time: ${candidate.loadDurationMs?.let { "$it ms" } ?: "(not available)"}")
            if (candidate.loadError.isNotBlank()) appendLine("Load error: ${candidate.loadError}")
            if (candidate.responseHeaders.isNotEmpty()) {
                appendLine("Response headers:")
                candidate.responseHeaders.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
                    appendLine("$name: $value")
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Captured stream request") },
        text = {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(details)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
