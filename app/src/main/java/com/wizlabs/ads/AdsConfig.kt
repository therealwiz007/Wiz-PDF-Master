package com.wizlabs.ads

/**
 * Per-app ads setup (built in the app's own `AdsSetup.kt`, never inside this package).
 * See WIZ_ADS_PLAN.md sections 2.3 and 3.10.
 */
data class AdUnits(
    val banner: String? = null,
    val native: String? = null,
    val interstitial: String? = null,
    val rewarded: String? = null,
)

data class AdCaps(
    val graceDaysAfterInstall: Int = 1,        // banner/native: nothing before this
    val graceSessions: Int = 2,                // nor before this many sessions
    val interstitialGraceDays: Int = 3,
    val interstitialGraceSessions: Int = 4,
    val interstitialMinSessionSeconds: Int = 90,
    val interstitialMinGapMinutes: Int = 15,
    val interstitialPerDay: Int = 3,
    val interstitialPerSession: Int = 1,
    val rewardedAdFreeHours: Int = 24,
)

/** ARGB ints, so `core` needs no Compose. */
data class AdPalette(
    val surface: Int, val line: Int, val text: Int, val muted: Int,
    val accent: Int, val onAccent: Int, val cornerDp: Int = 20,
)

data class AdsConfig(
    /**
     * Master switch compiled into the app. While false nothing ad-related ever loads or shows,
     * whatever the other settings say. Flip it only in the release that links the ad SDK.
     */
    val enabled: Boolean,
    val units: AdUnits = AdUnits(),
    val caps: AdCaps = AdCaps(),
    /** Screen names where nothing may load or show. */
    val noAdScreens: Set<String>,
    /** Screen names where a slot may appear at all. Anything else is treated as no-ad. */
    val adScreens: Set<String>,
    val productIdRemoveAds: String = "remove_ads",
    val remoteSwitchUrl: String? = null,
    val maxAdContentRating: String = "PG",
)
