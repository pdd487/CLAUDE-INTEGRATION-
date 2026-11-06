# QwiKShot & QwiKScan

Two floating-bubble apps for Android 9–15, built from one codebase:

| App | Package | Tap the bubble to… | Bubble |
| --- | --- | --- | --- |
| **QwiKShot** | `com.qwikshot.app` | take a screenshot (same as **Volume Down + Power**) | slate, camera reticle |
| **QwiKScan** | `com.qwikscan.app` | open **Google Drive's document scanner** | blue, document + amber scan line |

They install side by side, each with its own bubble, settings and
accessibility switch. Everything else is identical: drag to move, snap to the
nearest edge, idle fade, drag onto ✕ to hide, long-press for the dashboard.

## Install on a phone

1. Copy the APK to the phone and open it. Allow "Install unknown apps" for
   whatever app you opened it from.
2. Open the app (**QwiKShot** or **QwiKScan**).
3. **Step 2 – Accessibility service** → *Open Accessibility settings* → turn on
   **QwiKShot bubble** / **QwiKScan bubble**.
   - **Android 13+ and the switch is greyed out ("Restricted setting")?**
     That is Android protecting sideloaded apps. Tap *Open App info*, tap the
     **⋮** menu in the top corner → **Allow restricted settings**, then go back
     and turn the service on.
4. The bubble appears. Step 1 (overlay permission) is optional. It is only a
   fallback for ROMs that refuse accessibility overlays.
5. **QwiKScan only – Step 3, Google Drive:** tap **Choose a Drive folder** and
   pick the folder (for example `J&M_Temp`). Every scan then saves straight
   into it. Skip this and Drive asks where to save after each scan.

## Using the bubble

| Gesture | What it does |
| --- | --- |
| **Tap** | QwiKShot: screenshot (the bubble hides itself first so it's never in the picture). QwiKScan: opens Drive's scanner. |
| **Drag** | Moves it. Let go and it snaps to the nearest side, 8 dp from the edge. |
| **Drag onto ✕** | A target appears at the bottom while dragging. Drop the bubble on it to hide it. Turn it back on from the app. |
| **Long-press** (0.6 s) | Opens the app's dashboard. |

After 3 seconds untouched the bubble fades to the idle opacity; any touch
brings it back. Dashboard sliders change the bubble live: **size** 40–72 dp,
**idle opacity** 20–100 %, and in QwiKShot the **hide delay before screenshot**
50–300 ms (raise it if the bubble ever shows up in a screenshot). QwiKScan
docks a little lower by default so both bubbles can share an edge.

## Build

Needs JDK 17+ and the Android SDK (platform 35). Android Studio provides both.

```bash
cd apps/qwikshot
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or export ANDROID_HOME
./gradlew assembleRelease     # both apps, R8-shrunk (~2 MB each)
./gradlew assembleShotRelease # QwiKShot only
./gradlew assembleScanRelease # QwiKScan only
```

| Output | Path |
| --- | --- |
| QwiKShot | `app/build/outputs/apk/shot/release/app-shot-release.apk` |
| QwiKScan | `app/build/outputs/apk/scan/release/app-scan-release.apk` |

Use `assembleDebug` (or `assembleShotDebug` / `assembleScanDebug`) for debug builds.

### Release signing

Without extra setup, release builds are signed with the debug key so they
install straight away. To sign with your own key, copy
`keystore.properties.example` to `keystore.properties` (git-ignored), fill it
in, and rebuild. Android only installs an update over an existing copy if both
are signed with the same key. If you switch keys, or move to a build from
another machine, uninstall the app first.

## How it works

Both apps are the same `QwiKShotAccessibilityService`. It draws the bubble as
a `TYPE_ACCESSIBILITY_OVERLAY` window, which floats over every app with no extra
permission, no foreground service and no notification. The service config
subscribes to only the rarest event type and cannot read window content
(`canRetrieveWindowContent="false"`), so nothing runs between touches. The
`shot`/`scan` product flavor (`BuildConfig.DRIVE_SCAN_MODE`) decides what a tap
does; flavor resources in `src/scan/res` swap the name, colors and icons.

**QwiKShot tap**

```
bubble INVISIBLE + not touchable
  └─ wait hide delay (100 ms) ─▶ performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                                   └─ wait 600 ms ─▶ bubble VISIBLE + haptic CONFIRM
```

No MediaProjection. That API shows a consent dialog every session and keeps a
capture pipeline running; the global action is the system's own screenshot path.

**QwiKScan tap** (`DriveScan.kt`)

```
folder chosen?  yes ─▶ ScanToDriveActivity (data = Drive's folder URI) ─▶ scanner, saves to that folder
                no  ─▶ action SCAN_DOCUMENT ─▶ Drive's scanner, Drive asks where to save
Drive missing        ─▶ toast; dashboard offers "Get Google Drive"
```

Accessibility services are exempt from Android's background activity-start
limits, so the scanner opens on top of whatever app is showing.

## Google Drive integration

Drive has no documented API for "open the scanner". QwiKScan uses only
components that Drive **exports** for other apps, found by reading the manifest
and bytecode of **Google Drive 2.26.447.0** (versionCode 214723026, targetSdk 37).
If a Drive update breaks QwiKScan, re-check these first:

| Drive component | Exported | What QwiKScan uses it for |
| --- | --- | --- |
| `…common.androidshortcuts.CreateDocumentScanShortcutActivity` | yes, `CREATE_SHORTCUT` | Step 3 folder picker. It's the same thing a home-screen launcher calls to add Drive's "scan to folder" shortcut. |
| `…common.androidshortcuts.ScanToDriveActivity` | yes, no filters | Tap with a folder set. Needs the folder URI as intent data. |
| `…drive.capture.DocScannerExportedActivity` | yes, `SCAN_DOCUMENT` | Tap with no folder set. |
| `…drive.capture.DocScannerActivity` | **no** | The scanner itself. The launcher's "Scan" shortcut targets it directly, which other apps can't do. |

What the bytecode shows (read with `dexdump`; Drive classes are in
`classes2.dex`, the androidx shortcut helpers in `classes.dex`):

- `CreateDocumentScanShortcutActivity.r(EntrySpec)` builds
  `Intent(ScanToDriveActivity).setAction(MAIN).setData(folderUri)` with flags
  `NEW_TASK | CLEAR_TOP | SINGLE_TOP`, then returns it through androidx
  `createShortcutResultIntent` (`Lelm;.a`), which adds the legacy
  `EXTRA_SHORTCUT_INTENT` / `EXTRA_SHORTCUT_NAME` extras. QwiKScan stores only
  the component and data URI from that, and only if they point back into Drive.
- `ScanToDriveActivity.onCreate`: no data URI → Drive's "Unable to open
  document" message (branch at `0x0034`). With a URI, `Ljyc;.a` → `psk.bH(…, 3)`
  builds `DocScannerActivity` with `accountName` and `collectionEntrySpec`
  (the destination folder). A trashed folder opens Drive's trash dialog; a
  deleted one shows "Folder: … is deleted." Fix either by picking the folder again.
- `DocScannerExportedActivity.onCreate` is a trampoline:
  `getIntent().setClassName(DocScannerActivity)`, `startActivity`, `finish`.
  `DocScannerActivity` never checks the action. It reads `accountName`
  (default: last-used account) and the optional `collectionEntrySpec`.
- No caller-identity checks in any of these classes.
- Drive targets API 37, so on Android 14+ intents to its exported components
  must match a declared filter. QwiKScan sends `SCAN_DOCUMENT` with no data,
  which matches the trampoline's first filter. The launcher shortcut's own
  `SCAN_DOCUMENT_FROM_SHORTCUT` action would be rejected.

## Limits

- Apps that block screenshots (most banking apps, some video apps, incognito
  tabs) block QwiKShot too. Android shows its usual "can't take screenshot"
  message. That refusal happens inside the system after QwiKShot's request is
  accepted, so the bubble still gives its normal confirm pulse. QwiKShot's own
  "Screenshot failed" toast and double-buzz only appear when Android rejects the
  request outright.
- QwiKScan depends on Drive's exported components above. If a future Drive
  removes them, the bubble falls back to opening Drive's home screen and says so.
- Bubbles float over the lock screen too, like any accessibility overlay.

## Layout

```
app/src/
├── main/                                  shared by both apps
│   ├── AndroidManifest.xml
│   ├── java/com/qwikshot/app/
│   │   ├── QwiKShotAccessibilityService.kt   overlay, touch physics, tap actions
│   │   ├── DriveScan.kt                      Google Drive scanner launch + folder picker
│   │   ├── MainActivity.kt                   dashboard
│   │   └── QwiKShotPrefs.kt                  shared settings + ranges
│   └── res/                              QwiKShot look (slate/teal)
└── scan/                                  QwiKScan overrides
    ├── AndroidManifest.xml                <queries> for Google Drive
    └── res/                               name, blue/amber colors, scan glyph, icons
```
