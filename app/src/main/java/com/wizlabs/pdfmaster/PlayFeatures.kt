package com.wizlabs.pdfmaster

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import kotlinx.coroutines.withTimeoutOrNull

/** Where an optional Google Play services component stands on this phone. */
enum class ModuleState(val label: String) {
    CHECKING("Checking…"), READY("Ready"), DOWNLOADING("Downloading…"),
    MISSING("Not downloaded yet"), UNAVAILABLE("Not available on this phone")
}

/**
 * The scanner, the extra text readers and the QR reader are components of Google Play services.
 * Play services fetches them itself; this app has no network permission and only asks whether
 * they are there. Every call here fails soft: a phone without Play services just gets "unavailable".
 */
object PlayModules {
    fun playServices(ctx: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    /** Is the component behind [api] on the phone? False when that can't be found out in [timeoutMs]. */
    suspend fun available(ctx: Context, api: OptionalModuleApi, timeoutMs: Long = 2500): Boolean {
        if (!playServices(ctx)) return false
        return try {
            withTimeoutOrNull(timeoutMs) { ModuleInstall.getClient(ctx).areModulesAvailable(api).await().areModulesAvailable() } ?: false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /** Ask Play services to fetch the component (no screen is shown). [onState] is called on the main thread. */
    fun install(ctx: Context, api: OptionalModuleApi, onState: (ModuleState) -> Unit = {}) {
        if (!playServices(ctx)) { onState(ModuleState.UNAVAILABLE); return }
        runCatching {
            val client = ModuleInstall.getClient(ctx)
            var listener: InstallStatusListener? = null
            listener = InstallStatusListener { update ->
                val state = when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> ModuleState.READY
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED -> ModuleState.UNAVAILABLE
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> ModuleState.MISSING
                    else -> ModuleState.DOWNLOADING
                }
                onState(state)
                if (state != ModuleState.DOWNLOADING) listener?.let { runCatching { client.unregisterListener(it) } }
            }
            val request = ModuleInstallRequest.newBuilder().addApi(api).setListener(listener).build()
            client.installModules(request)
                .addOnSuccessListener { onState(if (it.areModulesAlreadyInstalled()) ModuleState.READY else ModuleState.DOWNLOADING) }
                .addOnFailureListener { onState(ModuleState.UNAVAILABLE) }
        }.onFailure { onState(ModuleState.UNAVAILABLE) }
    }
}

/**
 * Tick list of the scripts text recognition should read, with the state of each one's component.
 * Ticking a script asks Play services to fetch it; unticking only stops using it.
 */
@Composable
fun OcrLanguagePicker(chosen: Set<OcrLang>, onChange: (Set<OcrLang>) -> Unit) {
    val ctx = LocalContext.current
    val states = remember { mutableStateMapOf<OcrLang, ModuleState>() }
    LaunchedEffect(Unit) {
        for (l in OcrLang.entries) {
            if (l == OcrLang.LATIN) continue
            states[l] = ModuleState.CHECKING
            val ready = PlayModules.available(ctx, Ocr.client(l))
            // A download started below may have reported in meanwhile.
            if (states[l] == ModuleState.CHECKING) states[l] = when {
                ready -> ModuleState.READY
                PlayModules.playServices(ctx) -> ModuleState.MISSING
                else -> ModuleState.UNAVAILABLE
            }
            if (!ready && l in chosen) PlayModules.install(ctx.applicationContext, Ocr.client(l)) { states[l] = it }
        }
    }
    Column {
        OcrLang.entries.forEach { l ->
            val on = l in chosen
            val locked = l == OcrLang.LATIN
            val state = if (locked) ModuleState.READY else states[l] ?: ModuleState.CHECKING
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    on, enabled = !locked,
                    onCheckedChange = { tick ->
                        onChange(OcrLangs.normalize(if (tick) chosen + l else chosen - l))
                        if (tick && state != ModuleState.READY) {
                            states[l] = ModuleState.DOWNLOADING
                            PlayModules.install(ctx.applicationContext, Ocr.client(l)) { states[l] = it }
                        }
                    },
                    colors = CheckboxDefaults.colors(checkedColor = WizYellow, checkmarkColor = WizInk, disabledCheckedColor = WizYellowDeep)
                )
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(l.label, style = MaterialTheme.typography.titleSmall)
                    Text(l.detail, style = MaterialTheme.typography.bodySmall, color = WizMuted)
                }
                Text(
                    if (locked) "Built in" else state.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (state) { ModuleState.READY -> WizGood; ModuleState.UNAVAILABLE -> WizBad; else -> WizMuted }
                )
            }
        }
    }
}

/** Asked once, the first time text recognition is about to run: "Which languages do you scan?" */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrLanguageSheet(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var chosen by remember { mutableStateOf(Prefs.ocrLangs(ctx)) }
    val finish = { Prefs.setOcrLangs(ctx, chosen); Prefs.setOcrLangsAsked(ctx); onDone() }
    ModalBottomSheet(onDismissRequest = finish, containerColor = WizPanel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 14.dp)) {
            Text("Which languages do you scan?", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Searchable text works for English and other Latin scripts out of the box. Tick more only if you need them: " +
                    "each one is a small component that Google Play services adds to this phone. You can change this in Settings.",
                style = MaterialTheme.typography.bodySmall, color = WizMuted
            )
            Spacer(Modifier.height(8.dp))
            OcrLanguagePicker(chosen) { chosen = it }
            TextButton(onClick = finish, modifier = Modifier.align(Alignment.End)) {
                Text("Done", color = WizYellow, fontWeight = FontWeight.Bold)
            }
        }
    }
}
