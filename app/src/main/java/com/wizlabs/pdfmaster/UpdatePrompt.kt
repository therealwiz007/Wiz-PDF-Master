package com.wizlabs.pdfmaster

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

private enum class UpdateStage { NONE, AVAILABLE, DOWNLOADING, READY }

/** Reads what Play offers and records when this phone first saw it. Null when there is nothing to offer. */
private data class Offer(val info: AppUpdateInfo, val ageDays: Int)

private fun offerOf(ctx: Context, i: AppUpdateInfo): Offer? {
    val offered = i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
        i.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ||
        i.installStatus() == InstallStatus.DOWNLOADED
    if (!offered) return null
    val now = System.currentTimeMillis()
    val (storedV, storedAt) = Prefs.updateFirstSeen(ctx)
    val (v, at) = UpdateLogic.firstSeen(i.availableVersionCode(), storedV, storedAt, now)
    if (v != storedV || at != storedAt) Prefs.setUpdateFirstSeen(ctx, v, at)
    return Offer(i, UpdateLogic.ageDays(i.clientVersionStalenessDays(), at, now))
}

/**
 * Slim card offering a Play Store update, for the first [UpdateLogic.GRACE_DAYS] days. After that
 * [RequiredUpdateGate] takes over and the update is required. The Play Store app answers
 * "is there a newer version?" and does the download. Anything going wrong (no Play Store,
 * offline, sideloaded build) just means no card.
 */
@Composable
fun UpdateCard(modifier: Modifier = Modifier, onVisible: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    val manager = remember { runCatching { AppUpdateManagerFactory.create(ctx.applicationContext) }.getOrNull() } ?: return
    var stage by remember { mutableStateOf(UpdateStage.NONE) }
    var info by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var daysLeft by remember { mutableStateOf(UpdateLogic.GRACE_DAYS) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // Declined in the Play dialog: go back to offering it.
        if (result.resultCode != android.app.Activity.RESULT_OK && stage == UpdateStage.DOWNLOADING) stage = UpdateStage.AVAILABLE
    }

    DisposableEffect(manager) {
        val listener = InstallStateUpdatedListener { state ->
            when (state.installStatus()) {
                InstallStatus.DOWNLOADED -> stage = UpdateStage.READY
                InstallStatus.DOWNLOADING, InstallStatus.PENDING -> stage = UpdateStage.DOWNLOADING
                InstallStatus.FAILED, InstallStatus.CANCELED -> stage = UpdateStage.NONE
                else -> {}
            }
        }
        runCatching { manager.registerListener(listener) }
        onDispose { runCatching { manager.unregisterListener(listener) } }
    }
    LaunchedEffect(manager) {
        val i = runCatching { manager.appUpdateInfo.await() }.getOrNull() ?: return@LaunchedEffect
        val offer = offerOf(ctx, i) ?: return@LaunchedEffect
        info = i
        daysLeft = UpdateLogic.daysLeft(offer.ageDays)
        stage = when {
            UpdateLogic.mustUpdate(offer.ageDays) -> UpdateStage.NONE        // the required-update screen handles it
            i.installStatus() == InstallStatus.DOWNLOADED -> UpdateStage.READY
            i.installStatus() == InstallStatus.DOWNLOADING -> UpdateStage.DOWNLOADING
            i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && i.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> UpdateStage.AVAILABLE
            else -> UpdateStage.NONE
        }
    }
    LaunchedEffect(stage) { onVisible(stage != UpdateStage.NONE) }

    AnimatedVisibility(
        stage != UpdateStage.NONE, modifier,
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = WizYellow, contentColor = WizInk, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    when (stage) {
                        UpdateStage.READY -> "Update ready"
                        UpdateStage.DOWNLOADING -> "Downloading update…"
                        else -> if (daysLeft <= 1) "Update needed by tomorrow" else "New version · update within $daysLeft days"
                    },
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)
                )
                when (stage) {
                    UpdateStage.READY -> TextButton(onClick = { runCatching { manager.completeUpdate() } }) {
                        Text("Restart", color = WizInk, fontWeight = FontWeight.Bold)
                    }
                    UpdateStage.AVAILABLE -> TextButton(onClick = {
                        val i = info ?: return@TextButton
                        val started = runCatching {
                            manager.startUpdateFlowForResult(i, launcher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
                        }.getOrDefault(false)
                        stage = if (started) UpdateStage.DOWNLOADING else UpdateStage.NONE
                    }) { Text("Update", color = WizInk, fontWeight = FontWeight.Bold) }
                    else -> {}
                }
                if (stage != UpdateStage.READY) IconButton(onClick = {
                    // Not now: hidden until the next time the app opens. It comes back each launch
                    // during the grace days, then the update becomes required.
                    stage = UpdateStage.NONE
                }) { Icon(Icons.Outlined.Close, "Dismiss", Modifier.size(18.dp)) }
            }
        }
    }
}

private fun installedVersion(ctx: Context): Triple<Int, String, Pair<Long, Long>>? = runCatching {
    val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    Triple(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(p).toInt(), p.versionName ?: "", p.firstInstallTime to p.lastUpdateTime)
}.getOrNull()

/** Shown once on the first launch after an update: a few lines on what changed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet() {
    val ctx = LocalContext.current
    var versionName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val (code, name, times) = installedVersion(ctx) ?: return@LaunchedEffect
        val seen = Prefs.lastSeenVersion(ctx)
        if (seen != code) Prefs.setLastSeenVersion(ctx, code)
        if (UpdateLogic.showWhatsNew(seen, code, times.first, times.second)) versionName = name
    }
    val name = versionName ?: return
    ModalBottomSheet(onDismissRequest = { versionName = null }, containerColor = WizPanel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 22.dp, end = 22.dp, bottom = 18.dp)) {
            Text(stringResource(R.string.whats_new_title, name), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.whats_new_body), style = MaterialTheme.typography.bodyMedium, color = WizMuted)
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = { versionName = null }, modifier = Modifier.align(Alignment.End)) {
                Text("Got it", color = WizYellow, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Required update: once a Play version has been offered for [UpdateLogic.GRACE_DAYS] days, the
 * app opens Google Play's full-screen update and, if that is closed, stays behind this screen
 * until the update is installed. Checked at start and every time the app comes back to the front.
 * Offline or no Play Store means nothing can be checked, and the app keeps working.
 */
@Composable
fun RequiredUpdateGate() {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    val manager = remember { runCatching { AppUpdateManagerFactory.create(ctx.applicationContext) }.getOrNull() } ?: return
    var required by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var autoStarted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { /* blocked until done */ }

    fun openPlay() {
        val id = ctx.packageName
        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$id")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$id")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    fun start(i: AppUpdateInfo) {
        when {
            i.installStatus() == InstallStatus.DOWNLOADED -> runCatching { manager.completeUpdate() }
            i.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) || i.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                val ok = runCatching { manager.startUpdateFlowForResult(i, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()) }.getOrDefault(false)
                if (!ok) openPlay()
            }
            else -> openPlay()
        }
    }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumes by remember { mutableStateOf(0) }
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(resumes) {
        if (resumes == 0) return@LaunchedEffect
        val i = runCatching { manager.appUpdateInfo.await() }.getOrNull() ?: return@LaunchedEffect
        val offer = offerOf(ctx, i)
        val inProgress = i.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
        required = offer != null && (inProgress || UpdateLogic.mustUpdate(offer.ageDays))
        info = i
        // Open Play's update screen by itself once per app run (and always to resume one in progress);
        // after that the button below does it, so closing Play's screen doesn't loop.
        if (required && (inProgress || !autoStarted)) { autoStarted = true; start(i) }
    }

    if (!required) return
    androidx.activity.compose.BackHandler { activity?.finish() }
    Surface(color = WizBg, contentColor = WizText, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().padding(28.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(shape = RoundedCornerShape(26.dp), color = WizYellow, contentColor = WizInk) {
                Icon(Icons.Outlined.SystemUpdate, null, Modifier.padding(22.dp).size(40.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text("Update required", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "A newer Wiz PDF Master is on Google Play. Please update to keep using the app. Your scans and documents stay as they are.",
                style = MaterialTheme.typography.bodyMedium, color = WizMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(26.dp))
            androidx.compose.material3.Button(
                onClick = { info?.let { start(it) } ?: openPlay() },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk),
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()
            ) { Text("Update now", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp)) }
        }
    }
}
