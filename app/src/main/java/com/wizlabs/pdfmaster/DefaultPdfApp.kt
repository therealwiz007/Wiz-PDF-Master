package com.wizlabs.pdfmaster

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Open PDFs with Wiz PDF Master?" Android lets no app make itself the default; only the user can,
 * by choosing "Always" in the system's Open-with sheet. Some phones (seen on OPPO/ColorOS) forget that
 * choice when the app updates. This card offers, once per installed version, to open a small sample
 * PDF so that sheet appears. It never opens anything by itself, it can be dismissed, and nothing in
 * the app depends on it (Play policy: honest wording, user's own choice, no nagging).
 */
object DefaultPdfApp {
    const val SAMPLE_NAME = "Wiz PDF Master - default app.pdf"

    sealed interface State {
        data object IsDefault : State          // nothing to do
        data object NoDefault : State          // Android asks every time: the sample opens the sheet
        data class Other(val pkg: String, val label: String) : State   // another app is the default
    }

    private fun viewIntent(uri: Uri) = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")

    fun state(ctx: Context): State {
        val pm = ctx.packageManager
        val probe = viewIntent(Uri.parse("content://${ctx.packageName}.files/share/probe.pdf"))
        // Only one app on the phone opens PDFs: Android uses it without asking.
        val handlers = runCatching { pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
        if (handlers.size <= 1) return State.IsDefault
        val r = runCatching { pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull() ?: return State.NoDefault
        val pkg = r.activityInfo?.packageName ?: return State.NoDefault
        return when {
            pkg == ctx.packageName -> State.IsDefault
            handlers.none { it.activityInfo?.packageName == pkg } -> State.NoDefault   // the system "Open with" sheet
            else -> State.Other(pkg, runCatching { r.loadLabel(pm).toString() }.getOrDefault(pkg))
        }
    }

    /** One small page that confirms the choice; written once to cache/share for the FileProvider. */
    private suspend fun sample(ctx: Context): Uri = withContext(Dispatchers.IO) {
        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, SAMPLE_NAME)
        if (!f.exists()) {
            val doc = PdfDocument()
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.textSize = 26f; p.isFakeBoldText = true
            page.canvas.drawText("Wiz PDF Master", 60f, 120f, p)
            p.textSize = 16f; p.isFakeBoldText = false
            page.canvas.drawText("If you chose Wiz PDF Master and \"Always\",", 60f, 170f, p)
            page.canvas.drawText("PDFs from your files, mail and chats now open here.", 60f, 194f, p)
            doc.finishPage(page)
            f.outputStream().use { doc.writeTo(it) }
            doc.close()
        }
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }

    fun isSample(uri: Uri?): Boolean = uri?.lastPathSegment?.endsWith(SAMPLE_NAME) == true

    /** Opens the sample through the system sheet (no chooser wrapper, so "Always" is offered). */
    suspend fun askSystem(ctx: Context) {
        val i = viewIntent(sample(ctx)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) { toast(ctx, "No app can open PDFs") }
    }

    /** Another app is the default: its App info page has "Open by default → Clear defaults". */
    fun openAppInfo(ctx: Context, pkg: String) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast(ctx, "Couldn't open the app settings") }
    }

    fun versionCode(ctx: Context): Int = runCatching {
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)).toInt()
    }.getOrDefault(0)
}

/** Shown once per installed version, never with the update card, only while PDFs don't open here. */
@Composable
fun DefaultAppCard(modifier: Modifier = Modifier, hidden: Boolean, onVisible: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var state by remember { mutableStateOf<DefaultPdfApp.State>(DefaultPdfApp.State.IsDefault) }
    var dismissed by remember { mutableStateOf(Prefs.defaultCardDismissed(ctx) >= DefaultPdfApp.versionCode(ctx)) }
    var asked by remember { mutableStateOf(false) }

    // Re-check whenever the app comes back to the front (e.g. after the Open-with sheet or Settings).
    val lifecycle = LocalLifecycleOwner.current
    var resumes by remember { mutableStateOf(0) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(resumes) {
        state = withContext(Dispatchers.Default) { DefaultPdfApp.state(ctx) }
        if (asked && state == DefaultPdfApp.State.IsDefault) {
            asked = false
            toast(ctx, "PDFs now open in Wiz PDF Master")
        }
    }

    val show = !hidden && !dismissed && state != DefaultPdfApp.State.IsDefault
    LaunchedEffect(show) { onVisible(show) }

    fun notNow() {
        Prefs.setDefaultCardDismissed(ctx, DefaultPdfApp.versionCode(ctx))   // back only after the next update
        dismissed = true
    }

    AnimatedVisibility(show, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Surface(shape = RoundedCornerShape(16.dp), color = WizPanel, contentColor = WizText, border = BorderStroke(1.dp, WizLine), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(20.dp), tint = WizYellow)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Open PDFs with Wiz PDF Master?", style = MaterialTheme.typography.labelLarge)
                    Text(
                        when (val s = state) {
                            is DefaultPdfApp.State.Other -> "PDFs open in ${s.label}. In its App info, tap Open by default → Clear defaults."
                            else -> "Tap Set, choose Wiz PDF Master, then Always."
                        },
                        style = MaterialTheme.typography.bodySmall, color = WizMuted
                    )
                }
                TextButton(onClick = ::notNow) { Text("Not now", color = WizMuted) }
                TextButton(onClick = {
                    when (val s = state) {
                        is DefaultPdfApp.State.Other -> DefaultPdfApp.openAppInfo(ctx, s.pkg)
                        else -> { asked = true; scope.launch { DefaultPdfApp.askSystem(ctx) } }
                    }
                }) { Text(if (state is DefaultPdfApp.State.Other) "Open" else "Set", color = WizYellow, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
