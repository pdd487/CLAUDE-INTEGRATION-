# QwiKShot

A floating bubble for Android 9–15 that takes a screenshot when you tap it,
the same as pressing **Volume Down + Power**. The bubble hides itself for the
instant the capture fires, so it never shows up in the picture. Screenshots
save wherever your phone normally puts them, with the usual system preview.

Package: `com.qwikshot.app` · minSdk 28 · targetSdk 35 · Kotlin · Material 3

## Install on a phone

1. Copy `app-release.apk` (or `app-debug.apk`) to the phone and open it.
   Allow "Install unknown apps" for whatever app you opened it from.
2. Open **QwiKShot**.
3. **Step 2 – Accessibility service** → *Open Accessibility settings* →
   **QwiKShot bubble** → turn it on.
   - **Android 13+ and the switch is greyed out ("Restricted setting")?**
     That is Android protecting sideloaded apps. Tap *Open App info* in QwiKShot,
     tap the **⋮** menu in the top corner → **Allow restricted settings**, then go
     back and turn the service on.
4. The bubble appears. Step 1 (overlay permission) is optional. It is only a
   fallback for ROMs that refuse accessibility overlays.

## Using the bubble

| Gesture | What it does |
| --- | --- |
| **Tap** | Takes a screenshot. |
| **Drag** | Moves it. Let go and it snaps to the nearest side, 8 dp from the edge. |
| **Drag onto ✕** | A target appears at the bottom while dragging. Drop the bubble on it to hide it. Turn it back on from the app. |
| **Long-press** (0.6 s) | Opens the QwiKShot dashboard. |

After 3 seconds untouched it fades to the idle opacity; any touch brings it back
to full strength. The dashboard sliders change the bubble live:

- **Bubble size**: 40–72 dp (default 56)
- **Idle opacity**: 20–100 % (default 50)
- **Hide delay before screenshot**: 50–300 ms (default 100). Raise it if the
  bubble ever appears in a screenshot on a slower phone.

## Build

Needs JDK 17 or newer and the Android SDK (platform 35). Android Studio
provides both. From the command line:

```bash
cd apps/qwikshot
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or export ANDROID_HOME
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk (R8-shrunk, ~2 MB)
```

### Release signing

Without extra setup, `assembleRelease` signs with the debug key so the APK
installs straight away. To sign with your own key, copy
`keystore.properties.example` to `keystore.properties` (git-ignored), fill it
in, and rebuild. Android only installs an update over an existing copy if both
are signed with the same key. If you switch keys, or move to a build from
another machine, uninstall QwiKShot first.

## How it works

```
tap ─▶ bubble INVISIBLE + not touchable
        └─ wait hide delay (100 ms) ─▶ performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                                         └─ wait 600 ms ─▶ bubble VISIBLE + haptic CONFIRM
```

- **No MediaProjection.** That API shows a consent dialog every session and
  keeps a capture pipeline running. Instead, `QwiKShotAccessibilityService`
  hosts the bubble as a `TYPE_ACCESSIBILITY_OVERLAY` window (draws over every
  app with no extra permission) and calls `GLOBAL_ACTION_TAKE_SCREENSHOT`,
  which is the system's own screenshot path.
- **No battery drain.** The system keeps the service bound, so there is no
  foreground service and no notification. The service config subscribes to
  only the rarest event type and cannot read window content
  (`canRetrieveWindowContent="false"`). Nothing runs between touches.
- **Settings are live.** `MainActivity` writes to SharedPreferences; the
  service's `OnSharedPreferenceChangeListener` resizes, fades, or hides the
  bubble immediately. Position is saved as edge + height fraction, so the bubble
  stays put across rotation and size changes.
- **Tap vs drag.** Under 10 dp of travel and under 250 ms is a tap. Anything
  that moves further is a drag, and the bubble tracks the finger in real time.
  The 10 dp threshold is density-independent; a raw 10 px is only ~2.5 dp on
  modern phones, where ordinary finger wobble would cancel taps.

## Limits

- Apps that block screenshots (most banking apps, some video apps, incognito
  tabs) block QwiKShot too. Android shows its usual "can't take screenshot"
  message. That refusal happens inside the system after QwiKShot's request is
  accepted, so the bubble still gives its normal confirm pulse. QwiKShot's own
  "Screenshot failed" toast and double-buzz only appear when Android rejects the
  request outright.
- The bubble also floats over the lock screen, like any accessibility overlay.

## Layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/qwikshot/app/
│   ├── QwiKShotAccessibilityService.kt   overlay, touch physics, capture pipeline
│   ├── MainActivity.kt                   permissions, master toggle, sliders
│   └── QwiKShotPrefs.kt                  shared settings + ranges
└── res/
    ├── xml/accessibility_service_config.xml
    ├── layout/{activity_main, layout_floating_bubble, layout_close_target}.xml
    └── drawable/{bg_bubble_squircle, ic_shutter, …}.xml
```
