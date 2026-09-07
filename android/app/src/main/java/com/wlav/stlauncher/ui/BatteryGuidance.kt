package com.wlav.stlauncher.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wlav.stlauncher.R

/**
 * A one-time nudge toward exempting the app from battery optimisation.
 *
 * Shown at most once ever, and never when the exemption is already granted —
 * long background generation is the only case that needs it, and the app works
 * fine in the foreground without it.
 */
object BatteryGuidance {
    private const val PREFERENCES = "launcher_preferences"
    private const val KEY_SHOWN = "battery_guidance_shown"

    fun showSettings(activity: Activity) {
        val exempt =
            activity
                .getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(activity.packageName)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.battery_dialog_title)
            .setMessage(if (exempt) R.string.battery_exempt else R.string.battery_not_exempt)
            .setPositiveButton(R.string.action_open_settings) { _, _ -> openSettings(activity) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openSettings(activity: Activity) {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${activity.packageName}".toUri()),
        )
    }

    fun showOnce(activity: Activity) {
        val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_SHOWN, false)) return

        val powerManager = activity.getSystemService(PowerManager::class.java)
        if (powerManager?.isIgnoringBatteryOptimizations(activity.packageName) != false) return

        preferences.edit { putBoolean(KEY_SHOWN, true) }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.battery_dialog_title)
            .setMessage(R.string.battery_dialog_message)
            .setPositiveButton(R.string.action_open_settings) { _, _ ->
                openSettings(activity)
            }.setNegativeButton(R.string.action_later, null)
            .setCancelable(true)
            .show()
    }
}
