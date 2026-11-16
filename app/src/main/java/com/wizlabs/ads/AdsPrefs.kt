package com.wizlabs.ads

import android.content.Context
import android.content.SharedPreferences

/** "wiz_ads" preferences: kept apart from each app's own prefs (plan 2.2). */
class AdsPrefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("wiz_ads", Context.MODE_PRIVATE)

    var sessions: Int
        get() = p.getInt("sessions", 0)
        set(v) = p.edit().putInt("sessions", v).apply()

    var lastForegroundAt: Long
        get() = p.getLong("last_fg", 0)
        set(v) = p.edit().putLong("last_fg", v).apply()

    var sessionStartedAt: Long
        get() = p.getLong("session_start", 0)
        set(v) = p.edit().putLong("session_start", v).apply()

    /**
     * First launch of a build that can show ads. Stays 0 until then, so grace for existing users
     * counts from the ads release and not from today (plan 7.5).
     */
    var adsEraStart: Long
        get() = p.getLong("ads_era_start", 0)
        set(v) = p.edit().putLong("ads_era_start", v).apply()

    var proOwned: Boolean
        get() = p.getBoolean("pro_owned", false)
        set(v) = p.edit().putBoolean("pro_owned", v).apply()

    var adFreeUntil: Long
        get() = p.getLong("ad_free_until", 0)
        set(v) = p.edit().putLong("ad_free_until", v).apply()
}
