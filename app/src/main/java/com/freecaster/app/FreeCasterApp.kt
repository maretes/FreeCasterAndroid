package com.freecaster.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.color.DynamicColors

class FreeCasterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: colors from the wallpaper on Android 12+
        DynamicColors.applyToActivitiesIfAvailable(this)

        // English by default, even on a Ukrainian phone; the user can switch in the menu.
        // Done once, so the user's later choice is never overwritten.
        val prefs = getSharedPreferences("main", MODE_PRIVATE)
        if (!prefs.getBoolean("lang_init", false)) {
            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            }
            prefs.edit().putBoolean("lang_init", true).apply()
        }
    }
}
