package com.bepinex.android.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.bepinex.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Upper bound for in-memory log rendering. The full file is still shared via
// FileProvider; only the on-screen tail is inflated as composables.
private const val MAX_LOG_LINES = 3000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    logFilePath: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var truncated by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Unbounded readText() on the composition thread plus one giant Text layout
    // ANRs/OOMs on multi-MB logs. Tail the file off Main and recycle rows.
    LaunchedEffect(logFilePath) {
        logLines = emptyList()
        truncated = false
        loadError = null
        withContext(Dispatchers.IO) {
            try {
                val file = File(logFilePath)
                if (!file.exists()) {
                    loadError = "Log file not found:\n$logFilePath"
                } else {
                    val all = file.bufferedReader().useLines { it.toList() }
                    truncated = all.size > MAX_LOG_LINES
                    logLines = if (truncated) all.takeLast(MAX_LOG_LINES) else all
                }
            } catch (e: Exception) {
                loadError = "Failed to read log: ${e.message}"
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val file = File(logFilePath)
                        if (file.exists()) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                file
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "BepInEx Log")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                // The chooser is granted read access through
                                // ClipData. EXTRA_STREAM alone opens the sheet and
                                // then logs "Could not read content://… metadata …
                                // call setClipData() to ensure that the sharesheet
                                // is given permission" - a share that appears to
                                // work and delivers nothing. Measured on device.
                                clipData = android.content.ClipData.newRawUri(null, uri)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Log"))
                        }
                    }) {
                        Icon(Icons.Filled.Share, stringResource(R.string.log_share))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                if (truncated) {
                    Text(
                        text = "Showing last $MAX_LOG_LINES lines — share for the full log",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                val error = loadError
                if (error != null) {
                    Text(
                        text = error,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(logLines) { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
