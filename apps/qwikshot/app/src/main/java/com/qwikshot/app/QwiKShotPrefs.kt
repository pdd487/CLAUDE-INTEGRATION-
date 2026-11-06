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
        get() = raw.getFloat(KEY_BUBBLE_Y_FRACTION, BuildConfig.DEFAULT_Y_FRACTION).coerceIn(0f, 1f)

    fun saveBubblePosition(side: Int, yFraction: Float) = raw.edit {
        putInt(KEY_BUBBLE_SIDE, side)
        putFloat(KEY_BUBBLE_Y_FRACTION, yFraction.coerceIn(0f, 1f))
    }

    fun resetCustomization() = raw.edit {
        putInt(KEY_BUBBLE_SIZE_DP, SIZE_DEFAULT_DP)
        putInt(KEY_IDLE_OPACITY_PERCENT, OPACITY_DEFAULT_PERCENT)
        putInt(KEY_HIDE_DELAY_MS, DELAY_DEFAULT_MS)
    }

    /**
     * QwiKScan only: the "Name my files" Claude routine's fire URL and token, as
     * shown by claude.ai/code/routines when an API trigger is added. The token
     * can only start that one routine; it cannot read anything.
     */
    var routineUrl: String?
        get() = raw.getString(KEY_ROUTINE_URL, null)?.takeIf { it.isNotBlank() }
        set(value) = raw.edit { putString(KEY_ROUTINE_URL, value) }

    var routineToken: String?
        get() = raw.getString(KEY_ROUTINE_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = raw.edit { putString(KEY_ROUTINE_TOKEN, value) }

    val isClaudeNamingSetUp: Boolean
        get() = routineUrl != null && routineToken != null

    /** Link to the most recent run's Claude session, and when it started. */
    val lastRunUrl: String?
        get() = raw.getString(KEY_LAST_RUN_URL, null)
    val lastRunAtMillis: Long
        get() = raw.getLong(KEY_LAST_RUN_AT, 0L)

    fun saveLastRun(sessionUrl: String?, atMillis: Long) = raw.edit {
        putString(KEY_LAST_RUN_URL, sessionUrl)
        putLong(KEY_LAST_RUN_AT, atMillis)
    }

    /** QwiKScan only: the Drive folder every scan saves into, or null for "Drive asks". */
    var scanFolder: DriveScan.Folder?
        get() {
            val component = raw.getString(KEY_SCAN_FOLDER_COMPONENT, null) ?: return null
            val uri = raw.getString(KEY_SCAN_FOLDER_URI, null) ?: return null
            return DriveScan.Folder(component, uri, raw.getString(KEY_SCAN_FOLDER_NAME, null))
        }
        set(value) = raw.edit {
            if (value == null) {
                remove(KEY_SCAN_FOLDER_COMPONENT)
                remove(KEY_SCAN_FOLDER_URI)
                remove(KEY_SCAN_FOLDER_NAME)
            } else {
                putString(KEY_SCAN_FOLDER_COMPONENT, value.component)
                putString(KEY_SCAN_FOLDER_URI, value.uri)
                putString(KEY_SCAN_FOLDER_NAME, value.name)
            }
        }

    companion object {
        const val FILE_NAME = "qwikshot_prefs"

        const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        const val KEY_BUBBLE_SIZE_DP = "bubble_size_dp"
        const val KEY_IDLE_OPACITY_PERCENT = "idle_opacity_percent"
        const val KEY_HIDE_DELAY_MS = "hide_delay_ms"
        const val KEY_BUBBLE_SIDE = "bubble_side"
        const val KEY_BUBBLE_Y_FRACTION = "bubble_y_fraction"
        const val KEY_SCAN_FOLDER_COMPONENT = "scan_folder_component"
        const val KEY_SCAN_FOLDER_URI = "scan_folder_uri"
        const val KEY_SCAN_FOLDER_NAME = "scan_folder_name"
        const val KEY_ROUTINE_URL = "claude_routine_url"
        const val KEY_ROUTINE_TOKEN = "claude_routine_token"
        const val KEY_LAST_RUN_URL = "claude_last_run_url"
        const val KEY_LAST_RUN_AT = "claude_last_run_at"

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
    }
}
