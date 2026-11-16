package com.wizlabs.pdfmaster

import com.wizlabs.ads.AdsConfig
import com.wizlabs.ads.core.AdFormat
import com.wizlabs.ads.core.AdGate
import com.wizlabs.ads.core.AdState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdGateTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private val ready = AdState(
        now = now, graceStart = now - 5 * day, sessions = 10, sessionStartedAt = now - 10 * 60_000L,
        consentOk = true, sdkLinked = true
    )
    private fun gate(enabled: Boolean = true) = AdGate(AdsSetup.config().copy(enabled = enabled))

    @Test fun thisBuildShowsNothing() {
        // The shipped config is OFF: no format may show anywhere, whatever the state.
        val g = AdGate(AdsSetup.config())
        assertFalse(AdsSetup.ENABLED)
        for (f in AdFormat.values()) assertFalse(g.mayShow(f, AdsSetup.HOME, ready))
        assertEquals("ads switched off in this build", g.reason(ready, AdFormat.NATIVE, AdsSetup.HOME))
    }

    @Test fun noSdkMeansNothing() {
        assertFalse(gate().mayShow(AdFormat.BANNER, AdsSetup.TOOLS, ready.copy(sdkLinked = false)))
    }

    @Test fun allowedOnlyOnAdScreens() {
        val g = gate()
        assertTrue(g.mayShow(AdFormat.NATIVE, AdsSetup.HOME, ready))
        assertTrue(g.mayShow(AdFormat.BANNER, AdsSetup.TOOLS, ready))
        for (s in listOf("Camera", "Review", "Viewer", "Protect", "Qr", "Merge", "SomethingNew"))
            assertFalse(s, g.mayShow(AdFormat.BANNER, s, ready))
    }

    @Test fun graceAndPro() {
        val g = gate()
        assertFalse(g.mayShow(AdFormat.BANNER, AdsSetup.HOME, ready.copy(graceStart = now - day / 2)))
        assertFalse(g.mayShow(AdFormat.BANNER, AdsSetup.HOME, ready.copy(sessions = 1)))
        assertFalse(g.mayShow(AdFormat.BANNER, AdsSetup.HOME, ready.copy(proOwned = true)))
        assertFalse(g.mayShow(AdFormat.BANNER, AdsSetup.HOME, ready.copy(adFreeUntil = now + 1)))
        assertFalse(g.mayShow(AdFormat.BANNER, AdsSetup.HOME, ready.copy(consentOk = false)))
    }

    @Test fun lowRamGetsBannerOnly() {
        val g = gate()
        val low = ready.copy(lowRam = true)
        assertTrue(g.mayShow(AdFormat.BANNER, AdsSetup.TOOLS, low))
        assertFalse(g.mayShow(AdFormat.NATIVE, AdsSetup.HOME, low))
    }

    @Test fun interstitialCaps() {
        val g = gate()
        assertNull(g.reason(ready, AdFormat.INTERSTITIAL, AdsSetup.EXPORT_DONE))
        assertFalse(g.mayShow(AdFormat.INTERSTITIAL, AdsSetup.EXPORT_DONE, ready.copy(sessions = 3)))
        assertFalse(g.mayShow(AdFormat.INTERSTITIAL, AdsSetup.EXPORT_DONE, ready.copy(sessionStartedAt = now - 30_000)))
        assertFalse(g.mayShow(AdFormat.INTERSTITIAL, AdsSetup.EXPORT_DONE, ready.copy(interstitialsThisSession = 1)))
        assertFalse(g.mayShow(AdFormat.INTERSTITIAL, AdsSetup.EXPORT_DONE, ready.copy(interstitialsToday = 3)))
        assertFalse(g.mayShow(AdFormat.INTERSTITIAL, AdsSetup.EXPORT_DONE, ready.copy(lastInterstitialAt = now - 5 * 60_000L)))
    }

    @Test fun everyScreenHasAName() {
        // A new Screen without a name in AdsSetup.nameOf would not compile; this checks names are stable words.
        assertEquals("Home", AdsSetup.nameOf(Screen.Home))
        assertEquals("Qr", AdsSetup.nameOf(Screen.Qr))
    }
}
