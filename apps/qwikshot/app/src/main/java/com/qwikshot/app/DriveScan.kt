package com.qwikshot.app

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.net.toUri

/**
 * Opens Google Drive's own document scanner (QwiKScan flavor).
 *
 * Everything here uses components that Drive itself exports for other apps.
 * They were identified by reading the manifest and bytecode of Drive
 * 2.26.447.0. See "Google Drive integration" in README.md for the exact
 * classes and offsets.
 *
 * Two ways in:
 *  - **Quick scan**: the public `SCAN_DOCUMENT` action. Drive's exported
 *    trampoline forwards it to the same scanner as its home-screen "Scan"
 *    shortcut, and Drive asks where to save once the scan is done.
 *  - **Folder scan**: Drive's "scan to folder" shortcut. QwiKScan asks Drive to
 *    create that shortcut exactly the way a home-screen launcher does
 *    (`ACTION_CREATE_SHORTCUT`); Drive shows its folder picker and hands back a
 *    launch intent for `ScanToDriveActivity` with the folder baked into its
 *    data URI. Replaying that intent opens the scanner with the folder already
 *    set as the destination.
 */
object DriveScan {

    const val DRIVE_PACKAGE = "com.google.android.apps.docs"

    /** Public action handled by Drive's exported scanner trampoline. */
    private const val ACTION_SCAN_DOCUMENT = "com.google.android.apps.docs.SCAN_DOCUMENT"

    /** Exported CREATE_SHORTCUT activity behind Drive's "Drive scan" widget/shortcut. */
    private const val CREATE_SCAN_SHORTCUT_ACTIVITY =
        "com.google.android.apps.docs.common.androidshortcuts.CreateDocumentScanShortcutActivity"

    private const val TAG = "QwiKScan"

    /** A destination folder, as Drive described it when it created the shortcut. */
    data class Folder(
        /** Flattened ComponentName of Drive's scan-to-folder activity. */
        val component: String,
        /** Drive's own URI for the folder; opaque to QwiKScan. */
        val uri: String,
        /** Folder name for display, e.g. "J&M_Temp". */
        val name: String?,
    )

    enum class LaunchResult {
        /** Drive's scanner is opening. */
        OPENED,

        /** This Drive has no scanner entry point; Drive's home screen opened instead. */
        OPENED_DRIVE_ONLY,
        DRIVE_MISSING,
        FAILED,
    }

    fun isDriveInstalled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(DRIVE_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(DRIVE_PACKAGE, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Intent for [android.app.Activity.startActivityForResult]: Drive shows its folder picker. */
    fun folderPickerIntent(): Intent =
        Intent(Intent.ACTION_CREATE_SHORTCUT).setClassName(DRIVE_PACKAGE, CREATE_SCAN_SHORTCUT_ACTIVITY)

    /**
     * Reads the folder out of Drive's CREATE_SHORTCUT result. Drive returns a
     * standard shortcut result: EXTRA_SHORTCUT_INTENT (the launch intent) and
     * EXTRA_SHORTCUT_NAME (the folder name). Only the component and data URI are
     * kept, and only if they point back into Drive.
     *
     * Those two extras are deprecated in favour of ShortcutManager pin requests,
     * but a pin request can only be accepted by the default launcher. The legacy
     * extras, which androidx's createShortcutResultIntent still adds, are the only
     * part of the result another app can read.
     */
    @Suppress("DEPRECATION")
    fun parseFolderPickerResult(data: Intent?): Folder? {
        if (data == null) return null
        val launch: Intent = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
            } else {
                data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
            }
        } catch (e: RuntimeException) {
            // A malformed bundle (BadParcelableException and friends).
            Log.w(TAG, "Unreadable shortcut result from Drive", e)
            null
        } ?: return null
        val component = launch.component ?: return null
        val uri = launch.data ?: return null
        if (component.packageName != DRIVE_PACKAGE) return null
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
        return Folder(component.flattenToString(), uri.toString(), name)
    }

    /** Opens Drive's scanner, into [folder] when one is set. Call from any context. */
    fun launch(context: Context, folder: Folder?): LaunchResult {
        if (!isDriveInstalled(context)) return LaunchResult.DRIVE_MISSING
        if (folder != null) {
            folderScanIntent(folder)?.let { intent ->
                if (start(context, intent)) return LaunchResult.OPENED
            }
            // Fall through to a quick scan rather than doing nothing; Drive will
            // ask where to save.
            Log.w(TAG, "Folder scan could not start; falling back to quick scan")
        }
        if (start(context, quickScanIntent())) return LaunchResult.OPENED
        // Very old Drive without the scanner entry points: at least open Drive.
        val driveHome = context.packageManager.getLaunchIntentForPackage(DRIVE_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (driveHome != null && start(context, driveHome)) {
            LaunchResult.OPENED_DRIVE_ONLY
        } else {
            LaunchResult.FAILED
        }
    }

    private fun quickScanIntent(): Intent =
        // Implicit action pinned to Drive's package: survives Drive renaming the
        // trampoline class, and matches its intent filter as Android 14+ requires.
        Intent(ACTION_SCAN_DOCUMENT)
            .setPackage(DRIVE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun folderScanIntent(folder: Folder): Intent? {
        val component = ComponentName.unflattenFromString(folder.component) ?: return null
        if (component.packageName != DRIVE_PACKAGE) return null
        val uri: Uri = folder.uri.toUri()
        // Rebuilt from the two stored fields rather than replaying Drive's whole
        // intent; the flags match the ones Drive puts on its own shortcut.
        return Intent(Intent.ACTION_MAIN)
            .setComponent(component)
            .setData(uri)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity for $intent", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Drive refused $intent", e)
        false
    }
}
