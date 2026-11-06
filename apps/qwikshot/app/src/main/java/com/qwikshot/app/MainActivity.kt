package com.qwikshot.app

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.slider.Slider
import com.qwikshot.app.databinding.ActivityMainBinding
import kotlin.math.roundToInt

/**
 * Control dashboard: permission status, master toggle, and live bubble settings.
 * In QwiKScan it also shows Step 3, where the Google Drive scan folder is chosen.
 *
 * Every control writes straight to [QwiKShotPrefs]; the accessibility service
 * listens to the same preferences and updates the bubble as the sliders move.
 */
class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: QwiKShotPrefs
    private lateinit var sliders: List<SliderSetting>

    /** Guards against switch/slider listeners writing back values we are only displaying. */
    private var syncingUi = false

    private val serviceRunningListener: (Boolean) -> Unit = { runOnUiThread { refreshStatus() } }

    /**
     * QwiKScan: Drive's own "scan to folder" shortcut maker. Drive shows its folder
     * picker and returns a launch intent for that folder, the same result a home
     * screen launcher would get when you add Drive's scan shortcut.
     */
    private val driveFolderPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val folder = DriveScan.parseFolderPickerResult(result.data)
            if (folder == null) {
                toast(R.string.toast_drive_folder_missing)
            } else {
                prefs.scanFolder = folder
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The dashboard is always dark, so force light system-bar icons regardless
        // of the phone's own light/dark setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = QwiKShotPrefs(this)

        applySystemBarInsets()
        setupPermissionCards()
        setupDriveCard()
        setupMasterToggle()
        setupSliders()
        binding.versionText.text =
            getString(R.string.version_format, getString(R.string.app_name), BuildConfig.VERSION_NAME)
    }

    override fun onStart() {
        super.onStart()
        prefs.raw.registerOnSharedPreferenceChangeListener(this)
        QwiKShotAccessibilityService.addRunningListener(serviceRunningListener)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a Settings screen is the common way permissions change.
        refreshStatus()
        syncSlidersFromPrefs()
    }

    override fun onStop() {
        QwiKShotAccessibilityService.removeRunningListener(serviceRunningListener)
        prefs.raw.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        // The bubble's drag-to-dismiss flips the master toggle from the service side.
        when (key) {
            QwiKShotPrefs.KEY_BUBBLE_ENABLED,
            QwiKShotPrefs.KEY_SCAN_FOLDER_URI,
            null -> refreshStatus()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------------

    /** Edge-to-edge (mandatory on Android 15 at targetSdk 35): pad content clear of the bars. */
    private fun applySystemBarInsets() {
        val baseBottomPadding = binding.content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            // Top/side insets on the scroll view keep content out from under the
            // status bar. The bottom inset goes on the content instead, so it can
            // scroll behind the navigation bar yet still end fully clear of it.
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.content.updatePadding(bottom = baseBottomPadding + bars.bottom)
            insets
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Step 1 and Step 2
    // ---------------------------------------------------------------------------------------------

    private fun setupPermissionCards() {
        binding.overlayButton.setOnClickListener { openOverlaySettings() }
        binding.accessibilityButton.setOnClickListener { openAccessibilitySettings() }
        binding.appInfoButton.setOnClickListener { openAppInfo() }
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
        if (!launch(intent)) openAppInfo()
    }

    private fun openAccessibilitySettings() {
        val component = ComponentName(this, QwiKShotAccessibilityService::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            // Undocumented but widely honoured (AOSP, Pixel, Samsung): scrolls to and
            // highlights this app's service in the list. Ignored elsewhere, so harmless.
            putExtra(EXTRA_FRAGMENT_ARG_KEY, component)
            putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, component) })
        }
        if (!launch(intent)) toast(R.string.toast_settings_unavailable)
    }

    /** App info hosts "Allow restricted settings" for sideloaded apps on Android 13+. */
    private fun openAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
        if (!launch(intent)) toast(R.string.toast_settings_unavailable)
    }

    private fun launch(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    // ---------------------------------------------------------------------------------------------
    // Step 3 (QwiKScan): Google Drive
    // ---------------------------------------------------------------------------------------------

    private fun setupDriveCard() {
        binding.driveCard.isVisible = BuildConfig.DRIVE_SCAN_MODE
        // The hide delay only matters for screenshots.
        binding.delayGroup.isVisible = !BuildConfig.DRIVE_SCAN_MODE
        if (!BuildConfig.DRIVE_SCAN_MODE) return

        binding.driveButton.setOnClickListener {
            if (DriveScan.isDriveInstalled(this)) {
                try {
                    driveFolderPicker.launch(DriveScan.folderPickerIntent())
                } catch (e: ActivityNotFoundException) {
                    toast(R.string.toast_drive_scanner_unavailable)
                }
            } else {
                openDriveInPlayStore()
            }
        }
        binding.driveAskButton.setOnClickListener {
            prefs.scanFolder = null
            refreshStatus()
        }
    }

    private fun openDriveInPlayStore() {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=${DriveScan.DRIVE_PACKAGE}".toUri())
        val web = Intent(
            Intent.ACTION_VIEW,
            "https://play.google.com/store/apps/details?id=${DriveScan.DRIVE_PACKAGE}".toUri(),
        )
        if (!launch(market) && !launch(web)) toast(R.string.toast_settings_unavailable)
    }

    private fun refreshDriveCard() {
        if (!BuildConfig.DRIVE_SCAN_MODE) return
        val folder = prefs.scanFolder
        when {
            !DriveScan.isDriveInstalled(this) -> {
                setPill(binding.driveStatus, PillState.MISSING, R.string.status_not_installed)
                binding.driveBody.setText(R.string.drive_body_missing)
                binding.driveButton.setText(R.string.drive_button_install)
                binding.driveAskButton.isVisible = false
            }
            folder != null -> {
                setPill(binding.driveStatus, PillState.OK, R.string.status_folder_set)
                val name = folder.name?.takeIf { it.isNotBlank() } ?: getString(R.string.drive_folder_unnamed)
                binding.driveBody.text = getString(R.string.drive_body_folder, name)
                binding.driveButton.setText(R.string.drive_button_change)
                binding.driveAskButton.isVisible = true
            }
            else -> {
                setPill(binding.driveStatus, PillState.OK, R.string.status_ready)
                binding.driveBody.setText(R.string.drive_body_ask)
                binding.driveButton.setText(R.string.drive_button_choose)
                binding.driveAskButton.isVisible = false
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Master toggle
    // ---------------------------------------------------------------------------------------------

    private fun setupMasterToggle() {
        binding.masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingUi) return@setOnCheckedChangeListener
            prefs.bubbleEnabled = isChecked
            refreshStatus()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------------------------------------

    private fun refreshStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val serviceEnabled = QwiKShotAccessibilityService.isEnabledInSettings(this)
        val serviceRunning = QwiKShotAccessibilityService.isRunning

        // Step 1
        setPill(
            binding.overlayStatus,
            if (overlayGranted) PillState.OK else PillState.MISSING,
            if (overlayGranted) R.string.status_granted else R.string.status_missing,
        )
        binding.overlayButton.isEnabled = !overlayGranted
        binding.overlayButton.setText(
            if (overlayGranted) R.string.overlay_button_granted else R.string.overlay_button_grant,
        )

        // Step 2
        val serviceLabel = getString(R.string.accessibility_service_label)
        when {
            serviceRunning -> {
                setPill(binding.accessibilityStatus, PillState.OK, R.string.status_active)
                binding.accessibilityBody.setText(R.string.accessibility_body_running)
                binding.accessibilityButton.setText(R.string.accessibility_button_manage)
            }
            serviceEnabled -> {
                setPill(binding.accessibilityStatus, PillState.WARN, R.string.status_not_running)
                binding.accessibilityBody.text = getString(R.string.accessibility_body_stuck, serviceLabel)
                binding.accessibilityButton.setText(R.string.accessibility_button_enable)
            }
            else -> {
                setPill(binding.accessibilityStatus, PillState.MISSING, R.string.status_off)
                binding.accessibilityBody.text = getString(R.string.accessibility_body_off, serviceLabel)
                binding.accessibilityButton.setText(R.string.accessibility_button_enable)
            }
        }
        // Android 13+ blocks accessibility for sideloaded apps until the user allows
        // "restricted settings" from App info. Only worth showing until it works.
        val showRestrictedHelp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !serviceRunning
        binding.restrictedHint.isVisible = showRestrictedHelp
        binding.appInfoButton.isVisible = showRestrictedHelp

        // Step 3 (QwiKScan)
        refreshDriveCard()

        // Master toggle
        syncingUi = true
        binding.masterSwitch.isChecked = prefs.bubbleEnabled
        binding.masterSwitch.isEnabled = serviceRunning
        syncingUi = false
        binding.masterSummary.setText(
            when {
                !serviceRunning -> R.string.master_summary_needs_service
                prefs.bubbleEnabled -> R.string.master_summary_on
                else -> R.string.master_summary_off
            },
        )
    }

    private enum class PillState(@param:ColorRes val color: Int) {
        OK(R.color.qs_status_ok),
        WARN(R.color.qs_status_warn),
        MISSING(R.color.qs_status_missing),
    }

    private fun setPill(view: TextView, state: PillState, @StringRes text: Int) {
        view.setText(text)
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, state.color))
    }

    // ---------------------------------------------------------------------------------------------
    // Sliders
    // ---------------------------------------------------------------------------------------------

    private class SliderSetting(
        val slider: Slider,
        val valueText: TextView,
        val min: Int,
        val max: Int,
        val step: Int,
        @param:StringRes val format: Int,
        val read: () -> Int,
        val write: (Int) -> Unit,
    ) {
        /** Material's Slider throws if the value is off its step grid, so always snap. */
        fun snap(value: Int): Int {
            val steps = ((value - min).toFloat() / step).roundToInt()
            return (min + steps * step).coerceIn(min, max)
        }
    }

    private fun setupSliders() {
        sliders = listOf(
            SliderSetting(
                binding.sizeSlider, binding.sizeValue,
                QwiKShotPrefs.SIZE_MIN_DP, QwiKShotPrefs.SIZE_MAX_DP, QwiKShotPrefs.SIZE_STEP_DP,
                R.string.value_dp,
                read = { prefs.bubbleSizeDp },
                write = { prefs.bubbleSizeDp = it },
            ),
            SliderSetting(
                binding.opacitySlider, binding.opacityValue,
                QwiKShotPrefs.OPACITY_MIN_PERCENT, QwiKShotPrefs.OPACITY_MAX_PERCENT,
                QwiKShotPrefs.OPACITY_STEP_PERCENT,
                R.string.value_percent,
                read = { prefs.idleOpacityPercent },
                write = { prefs.idleOpacityPercent = it },
            ),
            SliderSetting(
                binding.delaySlider, binding.delayValue,
                QwiKShotPrefs.DELAY_MIN_MS, QwiKShotPrefs.DELAY_MAX_MS, QwiKShotPrefs.DELAY_STEP_MS,
                R.string.value_ms,
                read = { prefs.hideDelayMs },
                write = { prefs.hideDelayMs = it },
            ),
        )

        sliders.forEach { setting ->
            setting.slider.valueFrom = setting.min.toFloat()
            setting.slider.valueTo = setting.max.toFloat()
            setting.slider.stepSize = setting.step.toFloat()
            setting.slider.addOnChangeListener { _, value, fromUser ->
                val intValue = value.roundToInt()
                setting.valueText.text = getString(setting.format, intValue)
                // Written on every step so the bubble resizes/fades live under the finger.
                if (fromUser && !syncingUi) setting.write(intValue)
            }
        }

        binding.resetButton.setOnClickListener {
            prefs.resetCustomization()
            syncSlidersFromPrefs()
        }
        syncSlidersFromPrefs()
    }

    private fun syncSlidersFromPrefs() {
        syncingUi = true
        sliders.forEach { setting ->
            val value = setting.snap(setting.read())
            setting.slider.value = value.toFloat()
            setting.valueText.text = getString(setting.format, value)
        }
        syncingUi = false
    }

    private fun toast(@StringRes message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
    }
}
