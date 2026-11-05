package com.qwikshot.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Settings shared by [MainActivity] and [QwiKShotAccessibilityService].
 *
 * Both run in the same process and `getSharedPreferences` returns one cached
 * instance per file, so a write from the dashboard reaches the service's
 * OnSharedPreferenceChangeListener immediately. That listener is the whole
 * activity-to-service channel: no binder, no broadcasts.
 */
class QwiKShotPrefs(context: Context) {

    val raw: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Master toggle. Defaults to on so the bubble appears as soon as the service is enabled. */
    var bubbleEnabled: Boolean
        get() = raw.getBoolean(KEY_BUBBLE_ENABLED, true)
        set(value) = raw.edit { putBoolean(KEY_BUBBLE_ENABLED, value) }

    var bubbleSizeDp: Int
        get() = raw.getInt(KEY_BUBBLE_SIZE_DP, SIZE_DEFAULT_DP).coerceIn(SIZE_MIN_DP, SIZE_MAX_DP)
        set(value) = raw.edit { putInt(KEY_BUBBLE_SIZE_DP, value.coerceIn(SIZE_MIN_DP, SIZE_MAX_DP)) }

    var idleOpacityPercent: Int
        get() = raw.getInt(KEY_IDLE_OPACITY_PERCENT, OPACITY_DEFAULT_PERCENT)
            .coerceIn(OPACITY_MIN_PERCENT, OPACITY_MAX_PERCENT)
        set(value) = raw.edit {
            putInt(KEY_IDLE_OPACITY_PERCENT, value.coerceIn(OPACITY_MIN_PERCENT, OPACITY_MAX_PERCENT))
        }

    var hideDelayMs: Int
        get() = raw.getInt(KEY_HIDE_DELAY_MS, DELAY_DEFAULT_MS).coerceIn(DELAY_MIN_MS, DELAY_MAX_MS)
        set(value) = raw.edit { putInt(KEY_HIDE_DELAY_MS, value.coerceIn(DELAY_MIN_MS, DELAY_MAX_MS)) }

    /** Which edge the bubble is docked to: [SIDE_LEFT] or [SIDE_RIGHT]. */
    val bubbleSide: Int
        get() = if (raw.getInt(KEY_BUBBLE_SIDE, SIDE_RIGHT) == SIDE_LEFT) SIDE_LEFT else SIDE_RIGHT

    /**
     * Vertical position as a fraction of the usable screen height (0 = top, 1 = bottom).
     * Stored as a fraction rather than pixels so the bubble keeps its place across
     * rotation and size changes.
     */
    val bubbleYFraction: Float
        get() = raw.getFloat(KEY_BUBBLE_Y_FRACTION, Y_FRACTION_DEFAULT).coerceIn(0f, 1f)

    fun saveBubblePosition(side: Int, yFraction: Float) = raw.edit {
        putInt(KEY_BUBBLE_SIDE, side)
        putFloat(KEY_BUBBLE_Y_FRACTION, yFraction.coerceIn(0f, 1f))
    }

    fun resetCustomization() = raw.edit {
        putInt(KEY_BUBBLE_SIZE_DP, SIZE_DEFAULT_DP)
        putInt(KEY_IDLE_OPACITY_PERCENT, OPACITY_DEFAULT_PERCENT)
        putInt(KEY_HIDE_DELAY_MS, DELAY_DEFAULT_MS)
    }

    companion object {
        const val FILE_NAME = "qwikshot_prefs"

        const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        const val KEY_BUBBLE_SIZE_DP = "bubble_size_dp"
        const val KEY_IDLE_OPACITY_PERCENT = "idle_opacity_percent"
        const val KEY_HIDE_DELAY_MS = "hide_delay_ms"
        const val KEY_BUBBLE_SIDE = "bubble_side"
        const val KEY_BUBBLE_Y_FRACTION = "bubble_y_fraction"

        const val SIDE_LEFT = 0
        const val SIDE_RIGHT = 1

        const val SIZE_MIN_DP = 40
        const val SIZE_MAX_DP = 72
        const val SIZE_STEP_DP = 2
        const val SIZE_DEFAULT_DP = 56

        const val OPACITY_MIN_PERCENT = 20
        const val OPACITY_MAX_PERCENT = 100
        const val OPACITY_STEP_PERCENT = 5
        const val OPACITY_DEFAULT_PERCENT = 50

        const val DELAY_MIN_MS = 50
        const val DELAY_MAX_MS = 300
        const val DELAY_STEP_MS = 10
        const val DELAY_DEFAULT_MS = 100

        const val Y_FRACTION_DEFAULT = 0.35f
    }
}
