package com.wizlabs.ads.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.wizlabs.ads.AdPalette
import com.wizlabs.ads.WizAds
import com.wizlabs.ads.WIZ_ADS_SDK_LINKED
import com.wizlabs.ads.core.AdFormat

/**
 * Ad slots, placed where the plan puts them (3.10). Without the SDK they render nothing, take no
 * space and allocate nothing, so the screens look exactly as before. The SDK release replaces the
 * bodies with the real AdView / NativeAdView wrappers; call sites stay as they are.
 */
@Composable
fun WizBannerAd(screen: String, modifier: Modifier = Modifier, inline: Boolean = false) {
    val visible by WizAds.adsVisible.collectAsState()
    if (!WIZ_ADS_SDK_LINKED || !visible || !WizAds.mayShow(AdFormat.BANNER, screen)) return
    // SDK release: AndroidView { BannerHost ... }
}

@Composable
fun WizNativeAdCard(screen: String, palette: AdPalette, modifier: Modifier = Modifier) {
    val visible by WizAds.adsVisible.collectAsState()
    if (!WIZ_ADS_SDK_LINKED || !visible || !WizAds.mayShow(AdFormat.NATIVE, screen)) return
    // SDK release: AndroidView { WizNativeAdView(context, palette) } bound to a pooled NativeAd
}

/**
 * Interstitial at a true end-of-task break (PDF Master and Photo Fit only). Always runs [then]
 * exactly once; with no SDK it runs it straight away.
 */
fun showInterstitialAtBreak(screen: String, then: () -> Unit) {
    if (!WIZ_ADS_SDK_LINKED || !WizAds.mayShow(AdFormat.INTERSTITIAL, screen)) { then(); return }
    then()  // SDK release: InterstitialSlot.showAtBreak(activity, screen, then)
}
