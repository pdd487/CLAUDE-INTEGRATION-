package com.qwikshot.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Starts the "Name my files" Claude routine (QwiKScan flavor).
 *
 * The routine lives in Claude Code on the web (claude.ai/code/routines). Its
 * prompt is in `routines/name-my-files.md`: it finds generic file names in
 * Google Drive (Screenshot_…, IMG_…, Scanned_…) and renames them `MM.DD_Subject`.
 *
 * Starting it is one documented HTTPS call:
 * POST https://api.anthropic.com/v1/claude_code/routines/{trig_id}/fire with a
 * per-routine bearer token. The token can only start that one routine. It
 * cannot read anything, so storing it in this app's private preferences is low
 * risk. The call returns as soon as the run starts, with a link to watch it.
 */
object ClaudeRoutine {

    private const val TAG = "QwiKScan"
    private const val URL_PREFIX = "https://api.anthropic.com/v1/claude_code/routines/"
    private const val API_VERSION = "2023-06-01"
    private const val CHANNEL_ID = "claude_runs"
    private const val NOTIFICATION_ID = 41

    sealed interface Result {
        data class Started(val sessionUrl: String?) : Result
        data class Failed(val reason: Reason, val detail: String?) : Result
    }

    enum class Reason(@param:StringRes val message: Int) {
        NO_NETWORK(R.string.claude_error_network),
        BAD_TOKEN(R.string.claude_error_token),
        NOT_FOUND(R.string.claude_error_not_found),
        REJECTED(R.string.claude_error_rejected),
        NO_ACCESS(R.string.claude_error_access),
        RATE_LIMITED(R.string.claude_error_rate_limit),
        SERVER(R.string.claude_error_server),
    }

    /**
     * Accepts what people actually paste: the full fire URL from the routine's
     * API-trigger box, or just the routine id (`trig_…`). Returns null otherwise.
     */
    fun normalizeUrl(input: String): String? {
        val text = input.trim()
        Regex("""^trig_[A-Za-z0-9]+$""").matchEntire(text)?.let { return "$URL_PREFIX$text/fire" }
        val match = Regex("""^https://api\.anthropic\.com/v1/claude_code/routines/(trig_[A-Za-z0-9]+)/fire/?$""")
            .matchEntire(text) ?: return null
        return "$URL_PREFIX${match.groupValues[1]}/fire"
    }

    @WorkerThread
    fun fire(url: String, token: String, text: String): Result {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return Result.Failed(Reason.NO_NETWORK, e.message)
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("anthropic-version", API_VERSION)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                val sessionUrl = runCatching { JSONObject(body).optString("claude_code_session_url") }
                    .getOrNull()?.takeIf { it.startsWith("https://") }
                Result.Started(sessionUrl)
            } else {
                val detail = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull()
                val reason = when (code) {
                    400 -> Reason.REJECTED
                    401 -> Reason.BAD_TOKEN
                    403 -> Reason.NO_ACCESS
                    404 -> Reason.NOT_FOUND
                    429 -> Reason.RATE_LIMITED
                    else -> Reason.SERVER
                }
                Log.w(TAG, "Routine fire failed: HTTP $code ${detail.orEmpty()}")
                Result.Failed(reason, detail)
            }
        } catch (e: IOException) {
            Result.Failed(Reason.NO_NETWORK, e.message)
        } catch (e: JSONException) {
            Result.Failed(Reason.SERVER, e.message)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Fires the routine off the main thread and reports back with a toast and,
     * when allowed, a notification that opens the run in Claude. Safe to call
     * from an activity that finishes immediately.
     */
    fun start(context: Context, preview: Boolean) {
        val app = context.applicationContext
        val prefs = QwiKShotPrefs(app)
        val url = prefs.routineUrl
        val token = prefs.routineToken
        if (url == null || token == null) {
            toast(app, R.string.toast_claude_not_set_up)
            return
        }
        toast(app, if (preview) R.string.toast_claude_preview_starting else R.string.toast_claude_starting)
        val text = if (preview) {
            "Preview requested from the QwiKScan app: dry run. Do not rename anything."
        } else {
            "Started from the QwiKScan home-screen button."
        }
        val main = Handler(Looper.getMainLooper())
        thread(name = "claude-routine-fire") {
            val result = fire(url, token, text)
            main.post { report(app, prefs, result, preview) }
        }
    }

    private fun report(app: Context, prefs: QwiKShotPrefs, result: Result, preview: Boolean) {
        when (result) {
            is Result.Started -> {
                prefs.saveLastRun(result.sessionUrl, System.currentTimeMillis())
                toast(app, if (preview) R.string.toast_claude_preview_started else R.string.toast_claude_started)
                notifyStarted(app, result.sessionUrl, preview)
            }
            is Result.Failed -> toast(app, result.reason.message)
        }
    }

    // Only QwiKScan reaches this, and its manifest declares POST_NOTIFICATIONS; the
    // runtime check below covers Android 13+ where the user can refuse it. Lint
    // flags it for QwiKShot, which compiles this file but never declares or needs it.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun notifyStarted(app: Context, sessionUrl: String?, preview: Boolean) {
        if (sessionUrl == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(app)
        val channel = NotificationChannel(
            CHANNEL_ID,
            app.getString(R.string.claude_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = app.getString(R.string.claude_channel_description) }
        app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(Intent.ACTION_VIEW, sessionUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name_files)
            .setContentTitle(
                app.getString(if (preview) R.string.claude_notif_preview_title else R.string.claude_notif_title),
            )
            .setContentText(app.getString(R.string.claude_notif_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission revoked", e)
        }
    }

    private fun toast(app: Context, @StringRes message: Int) {
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
}
