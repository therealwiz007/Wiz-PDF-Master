package com.wizlabs.pdfmaster

import androidx.compose.ui.graphics.toArgb
import com.wizlabs.ads.AdPalette
import com.wizlabs.ads.AdsConfig
import com.wizlabs.ads.AdCaps

/**
 * PDF Master's side of the shared ads package (WIZ_ADS_PLAN.md 3.4 and 3.10).
 * Ads are OFF in this build: [ENABLED] is false and no ad SDK is linked, so every slot renders
 * nothing. The placements, screen rules and caps are wired so the ads release only has to link
 * the SDK, add the unit IDs and flip the switch.
 */
object AdsSetup {
    const val ENABLED = false

    // Stable screen names for the gate (never derived from toString: those carry document keys).
    const val HOME = "Home"
    const val TOOLS = "Tools"
    const val SETTINGS = "Settings"
    const val EXPORT_DONE = "export_done"

    /** Ads may appear only here (plan 3.4). */
    private val adScreens = setOf(HOME, TOOLS, SETTINGS, EXPORT_DONE)

    /** Never anything here: working screens, the camera and anything private (plan 3.4). */
    private val noAdScreens = setOf(
        "Camera", "Review", "Crop", "Viewer", "Protect", "Form", "Qr", "Annotate",
        "Stamp", "Pages", "Merge", "Compress", "ToJpg"
    )

    /** Native card in the library only once there are this many documents (plan 3.10). */
    const val NATIVE_MIN_DOCS = 3
    /** ...and it sits after this many rows. */
    const val NATIVE_AFTER_ROW = 3

    fun config() = AdsConfig(
        enabled = ENABLED,
        caps = AdCaps(interstitialPerDay = 3, interstitialMinGapMinutes = 15),
        noAdScreens = noAdScreens,
        adScreens = adScreens,
        remoteSwitchUrl = "https://therealwiz007.github.io/wiz-config/ads-pdfmaster.json",
    )

    val palette: AdPalette
        get() = AdPalette(
            surface = WizPanel.toArgb(), line = WizLine.toArgb(), text = WizText.toArgb(),
            muted = WizMuted.toArgb(), accent = WizYellow.toArgb(), onAccent = WizInk.toArgb()
        )

    fun nameOf(screen: Screen): String = when (screen) {
        Screen.Home -> HOME
        Screen.Tools -> TOOLS
        Screen.Settings -> SETTINGS
        Screen.Camera -> "Camera"
        Screen.Review -> "Review"
        is Screen.Crop -> "Crop"
        is Screen.Viewer -> "Viewer"
        Screen.Merge -> "Merge"
        is Screen.Pages -> "Pages"
        is Screen.Compress -> "Compress"
        is Screen.ToJpg -> "ToJpg"
        is Screen.Protect -> "Protect"
        is Screen.Annotate -> "Annotate"
        is Screen.Stamp -> "Stamp"
        is Screen.Form -> "Form"
        Screen.Qr -> "Qr"
    }
}
