package com.wizlabs.pdfmaster

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object Camera : Screen
    data object Review : Screen
    data class Crop(val index: Int) : Screen
    data class Viewer(val ref: PdfRef) : Screen
    data object Settings : Screen
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    /** Replace the top screen (e.g. Review -> Viewer after saving, so Back goes home). */
    fun replace(s: Screen) { if (stack.size > 1) stack[stack.lastIndex] = s else stack.add(s) }
    fun popTo(s: Screen) { while (stack.size > 1 && stack.last() != s) stack.removeAt(stack.lastIndex) }
}

class MainActivity : ComponentActivity() {
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { WizTheme { AppRoot(nav) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        } ?: return
        openExternal(this, nav, uri)
    }
}

fun openExternal(ctx: Context, nav: Nav, uri: Uri) {
    runCatching {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val (name, _) = queryNameSize(ctx, uri)
    Library.touchRecent(ctx, uri)
    nav.push(Screen.Viewer(PdfRef.External(uri, name.removeSuffix(".pdf").removeSuffix(".PDF"))))
}

@Composable
fun AppRoot(nav: Nav) {
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    // Surface sets the default text/icon colour for every screen (otherwise plain Text is black).
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = WizBg, contentColor = WizText) {
        AnimatedContent(
            targetState = nav.current,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = .97f)) togetherWith fadeOut(tween(160))
            },
            label = "nav"
        ) { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(nav)
                Screen.Camera -> CameraScreen(nav)
                Screen.Review -> ReviewScreen(nav)
                is Screen.Crop -> CropScreen(nav, screen.index)
                is Screen.Viewer -> ViewerScreen(nav, screen.ref)
                Screen.Settings -> SettingsScreen(nav)
            }
        }
    }
}

// ---- Sharing / exporting -------------------------------------------------------------------

/** Copy [doc] to a nicely named file in cache/share and return a FileProvider Uri for it. */
fun shareUriFor(ctx: Context, doc: Doc): Uri {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    dir.listFiles()?.filter { it.name.endsWith(".pdf") && System.currentTimeMillis() - it.lastModified() > 3_600_000 }
        ?.forEach { it.delete() }
    val f = File(dir, safeFileName(doc.name) + ".pdf")
    Library.fileOf(ctx, doc.id).copyTo(f, overwrite = true)
    return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
}

fun sharePdf(ctx: Context, uri: Uri, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, "Share text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun copyDocTo(ctx: Context, doc: Doc, target: Uri): Boolean = runCatching {
    ctx.contentResolver.openOutputStream(target, "w")?.use { out ->
        Library.fileOf(ctx, doc.id).inputStream().use { it.copyTo(out) }
    } != null
}.getOrDefault(false)

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
