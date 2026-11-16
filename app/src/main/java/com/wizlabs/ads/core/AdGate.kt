package com.wizlabs.ads.core

import com.wizlabs.ads.AdsConfig

enum class AdFormat { BANNER, NATIVE, INTERSTITIAL, REWARDED }

/** Everything the gate needs to know, as plain values, so the rules are unit-testable on the JVM. */
data class AdState(
    val now: Long,
    /** max(first install, first launch of the ads release): grace counts from here (plan 7.5). */
    val graceStart: Long,
    val sessions: Int,
    val sessionStartedAt: Long,
    val proOwned: Boolean = false,
    val premium: Boolean = false,
    val adFreeUntil: Long = 0,
    val remoteEnabled: Boolean = true,
    val consentOk: Boolean = false,
    val sdkLinked: Boolean = false,
    val lowRam: Boolean = false,
    val batterySaver: Boolean = false,
    val interstitialsToday: Int = 0,
    val interstitialsThisSession: Int = 0,
    val lastInterstitialAt: Long = 0,
)

/**
 * Who may see which format right now (plan 2.3). The first failing rule wins:
 * compiled switch, SDK linked, Pro, Premium, ad-free window, remote switch, grace, consent,
 * screen, low RAM / Battery Saver, per-format caps.
 */
class AdGate(private val config: AdsConfig) {

    fun adsAllowedAtAll(s: AdState): Boolean = reason(s, AdFormat.BANNER, null) == null

    fun mayShow(format: AdFormat, screen: String, s: AdState): Boolean = reason(s, format, screen) == null

    /** Why nothing shows (null = allowed). Handy in debug logs and tests. */
    fun reason(s: AdState, format: AdFormat, screen: String?): String? {
        val c = config.caps
        return when {
            !config.enabled -> "ads switched off in this build"
            !s.sdkLinked -> "no ad SDK in this build"
            s.proOwned -> "Pro"
            s.premium -> "Wiz Premium"
            s.adFreeUntil > s.now -> "ad-free window"
            !s.remoteEnabled -> "remote switch off"
            format == AdFormat.REWARDED -> if (s.consentOk) null else "no consent"   // rewarded is opt-in: no grace
            days(s) < (if (format == AdFormat.INTERSTITIAL) c.interstitialGraceDays else c.graceDaysAfterInstall) -> "grace (days)"
            s.sessions < (if (format == AdFormat.INTERSTITIAL) c.interstitialGraceSessions else c.graceSessions) -> "grace (sessions)"
            !s.consentOk -> "no consent"
            screen != null && (screen in config.noAdScreens || screen !in config.adScreens) -> "no-ad screen"
            s.lowRam && format != AdFormat.BANNER -> "low-RAM phone: banner only"
            s.batterySaver && (format == AdFormat.NATIVE || format == AdFormat.INTERSTITIAL) -> "Battery Saver"
            format == AdFormat.INTERSTITIAL -> interstitialReason(s)
            else -> null
        }
    }

    private fun interstitialReason(s: AdState): String? {
        val c = config.caps
        return when {
            s.now - s.sessionStartedAt < c.interstitialMinSessionSeconds * 1000L -> "session too young"
            s.interstitialsThisSession >= c.interstitialPerSession -> "session cap"
            s.interstitialsToday >= c.interstitialPerDay -> "daily cap"
            s.lastInterstitialAt > 0 && s.now - s.lastInterstitialAt < c.interstitialMinGapMinutes * 60_000L -> "too soon after the last one"
            else -> null
        }
    }

    private fun days(s: AdState) = (s.now - s.graceStart).coerceAtLeast(0) / 86_400_000L
}
