package com.wizlabs.pdfmaster

import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    var filter by remember { mutableStateOf(Prefs.defaultFilter(ctx)) }
    var auto by remember { mutableStateOf(Prefs.autoCapture(ctx)) }
    var ocr by remember { mutableStateOf(Prefs.ocrDefault(ctx)) }
    var page by remember { mutableStateOf(Prefs.pageSize(ctx)) }
    var night by remember { mutableStateOf(Prefs.nightReading(ctx)) }
    var privacy by remember { mutableStateOf(false) }
    val docs by Library.docs.collectAsState()
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    Column(Modifier.fillMaxSize().background(WizBg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding()) {
            SectionHeader("Scanning")
            Card {
                Text("Default look", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScanFilter.entries.forEach { f -> WizChip(f.label, filter == f, { filter = f; Prefs.setDefaultFilter(ctx, f) }) }
                }
                Spacer(Modifier.height(14.dp))
                ToggleRow("Auto capture", "Takes the photo by itself when the page is steady", auto) { auto = it; Prefs.setAutoCapture(ctx, it) }
            }
            SectionHeader("Saving")
            Card {
                ToggleRow("Make text searchable", "Run OCR on every new scan so you can search and copy text", ocr) { ocr = it; Prefs.setOcrDefault(ctx, it) }
                Spacer(Modifier.height(14.dp))
                Text("Page size", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageSize.entries.forEach { p -> WizChip(p.label, page == p, { page = p; Prefs.setPageSize(ctx, p) }) }
                }
            }
            SectionHeader("Reading")
            Card {
                ToggleRow("Night reading", "Dark pages with light text, easier on the eyes at night", night) { night = it; Prefs.setNightReading(ctx, it) }
            }
            SectionHeader("Privacy")
            Card {
                Text("Everything stays on this phone", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Wiz PDF Master has no internet permission. Scanning, text recognition and PDF creation all run on your device. " +
                        "No accounts, no ads, no tracking. ${docs.size} document${if (docs.size == 1) "" else "s"} stored privately in the app.",
                    style = MaterialTheme.typography.bodySmall, color = WizMuted
                )
                Spacer(Modifier.height(10.dp))
                Surface(onClick = { privacy = true }, color = WizPanelHi, contentColor = WizText, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Privacy policy", modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, null, tint = WizMuted)
                    }
                }
            }
            SectionHeader("About")
            Card {
                Text("Wiz PDF Master $version", style = MaterialTheme.typography.titleSmall)
                Text("by Wiz Labs", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Open-source engines: OpenCV (Apache 2.0), PDFBox-Android (Apache 2.0), Google ML Kit on-device text recognition, AndroidX CameraX.",
                    style = MaterialTheme.typography.bodySmall, color = WizMuted
                )
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (privacy) Dialog(onDismissRequest = { privacy = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = WizBg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { privacy = false }) { Icon(Icons.Outlined.Close, "Close") }
                    Text("Privacy policy", style = MaterialTheme.typography.titleMedium)
                }
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = false
                            settings.allowFileAccess = false
                            setBackgroundColor(android.graphics.Color.parseColor("#0B0B0D"))
                            loadUrl("file:///android_asset/privacy-policy.html")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = WizPanel, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, WizLine), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = WizMuted)
        }
        Spacer(Modifier.height(1.dp))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = WizYellow, checkedThumbColor = WizInk))
    }
}
