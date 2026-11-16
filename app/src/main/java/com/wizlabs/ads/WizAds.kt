package com.wizlabs.ads

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import com.wizlabs.ads.core.AdFormat
import com.wizlabs.ads.core.AdGate
import com.wizlabs.ads.core.AdState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Version of the shared package; stamped by `wiz-ads/sync.sh`. Never edit the copy inside an app. */
const val WIZ_ADS_VERSION = "0.1"

/**
 * True only in a build that links the ad SDK (Next-Gen GMA, UMP, Billing). This version is the
 * SDK-free groundwork: screens, placements and rules are wired, but nothing can ever load.
 */
const val WIZ_ADS_SDK_LINKED = false

/**
 * One entry point per app (plan 2.3). `install` is cheap and does no SDK work; it may run in
 * Application.onCreate. Nothing here touches the network.
 */
object WizAds {
    private var config: AdsConfig? = null
    private var prefs: AdsPrefs? = null
    private var gate: AdGate? = null
    private var appCtx: Context? = null

    private val _screen = MutableStateFlow("")
    val currentScreen: StateFlow<String> = _screen.asStateFlow()

    /** False while there is no SDK, in grace, Pro, ad-free window, etc. Slots render nothing then. */
    private val _adsVisible = MutableStateFlow(false)
    val adsVisible: StateFlow<Boolean> = _adsVisible.asStateFlow()

    fun install(app: Context, config: AdsConfig) {
        this.config = config
        this.appCtx = app.applicationContext
        this.prefs = AdsPrefs(app)
        this.gate = AdGate(config)
    }

    /** Counts sessions: a cold start, or coming back after 30+ minutes away (plan 2.3). */
    fun onActivityStart(activity: Activity) {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        if (p.lastForegroundAt == 0L || now - p.lastForegroundAt > SESSION_GAP_MS) {
            p.sessions = p.sessions + 1
            p.sessionStartedAt = now
        }
        p.lastForegroundAt = now
        refresh()
    }

    fun onActivityStop() {
        prefs?.lastForegroundAt = System.currentTimeMillis()
    }

    /** The app calls this from its navigation `when`, with a stable screen name. */
    fun onScreen(name: String) {
        _screen.value = name
        refresh()
    }

    fun mayShow(format: AdFormat, screen: String): Boolean {
        val g = gate ?: return false
        return g.mayShow(format, screen, state() ?: return false)
    }

    /** Why nothing shows right now (for Settings/debug). */
    fun whyNot(format: AdFormat, screen: String): String =
        state()?.let { gate?.reason(it, format, screen) } ?: "not installed"

    private fun refresh() {
        val g = gate ?: return
        _adsVisible.value = state()?.let { g.adsAllowedAtAll(it) } ?: false
    }

    private fun state(): AdState? {
        val p = prefs ?: return null
        val ctx = appCtx ?: return null
        val now = System.currentTimeMillis()
        return AdState(
            now = now,
            graceStart = maxOf(installTime(ctx), p.adsEraStart.takeIf { it > 0 } ?: now),
            sessions = p.sessions,
            sessionStartedAt = p.sessionStartedAt,
            proOwned = p.proOwned,
            adFreeUntil = p.adFreeUntil,
            consentOk = false,                 // UMP arrives with the SDK
            sdkLinked = WIZ_ADS_SDK_LINKED,
            lowRam = runCatching { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice }.getOrDefault(true),
            batterySaver = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }.getOrDefault(false),
        )
    }

    private fun installTime(ctx: Context): Long = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime
    }.getOrDefault(0L)

    private const val SESSION_GAP_MS = 30 * 60_000L
}
