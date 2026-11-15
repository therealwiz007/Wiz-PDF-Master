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

/**
 * Slim card offering a Play Store update. The app itself has no network access: the question
 * "is there a newer version?" is answered by the Play Store app on the phone, which also does
 * the download. Anything going wrong (no Play Store, offline, sideloaded build) just means no card.
 */
@Composable
fun UpdateCard(modifier: Modifier = Modifier, onVisible: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    val manager = remember { runCatching { AppUpdateManagerFactory.create(ctx.applicationContext) }.getOrNull() } ?: return
    var stage by remember { mutableStateOf(UpdateStage.NONE) }
    var info by remember { mutableStateOf<AppUpdateInfo?>(null) }
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
        info = i
        val (snoozedVersion, snoozedAt) = Prefs.updateSnooze(ctx)
        stage = when {
            i.installStatus() == InstallStatus.DOWNLOADED -> UpdateStage.READY
            i.installStatus() == InstallStatus.DOWNLOADING -> UpdateStage.DOWNLOADING
            i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && i.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) &&
                !UpdateLogic.snoozed(i.availableVersionCode(), snoozedVersion, snoozedAt, System.currentTimeMillis()) -> UpdateStage.AVAILABLE
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
                        else -> "New version available"
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
                    // Not now: stay quiet about this version for three days.
                    info?.let { Prefs.setUpdateSnooze(ctx, it.availableVersionCode(), System.currentTimeMillis()) }
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
