# Android Auto Testing

> This guide documents the experimental Android Auto support in the `chrober` forks. It applies to the fork-only `build/android-auto-apk` branches and is not part of the upstream projects or their pull requests.

The Android Auto experience is split between two applications:

- `Lyrion (chrober)` provides the Android Auto browsing interface.
- `Squeezelite (chrober)` is the local LMS player and produces the audio.

For the two APKs to work together, start Squeezelite and select `Squeezelite (chrober)` as the local player application in Lyrion's settings.

## Test An APK In A Car

These steps are for a person installing the signed APKs from the GitHub Actions artifacts. Android system developer options, USB debugging, Android Studio, and the Desktop Head Unit are not required.

1. Install both fork APKs. Their fork-specific application IDs and names allow them to coexist with the upstream apps.
2. Open `Squeezelite (chrober)` on the phone, configure or discover the LMS server, and start the player.
3. Open `Lyrion (chrober)`, connect to the same LMS server, then set **Settings** > **Local player** > **Player app** to **Squeezelite (chrober)**.
4. Enable Android Auto developer mode and allow apps from unknown sources:
   - Open Android Auto settings on the phone. Searching the Android settings app for `Android Auto` is usually the quickest way to reach them.
   - Tap **Version** repeatedly until Android Auto confirms that developer mode is enabled.
   - Open the overflow menu, choose **Developer settings**, and enable **Unknown sources**.
5. Connect the phone to the car with the normal wired or wireless Android Auto method. Launch `Lyrion (chrober)` from the Android Auto launcher.

Android Auto labels and menu placement vary a little by Android Auto version and device manufacturer. Updating Android Auto from Google Play before testing is recommended.

## Develop With The Desktop Head Unit

The Desktop Head Unit (DHU) emulates a standard Android Auto head unit on a laptop. It is useful for iterating on the Android Auto UI, media browsing, transport controls, and focus behaviour before using a car. It cannot reproduce every physical head unit or phone-vendor audio-routing behaviour, so real-car testing remains necessary.

For DHU versions, platform-specific prerequisites, and the complete command reference, see the [official Android DHU guide](https://developer.android.com/training/cars/testing/dhu).

### Prerequisites

- Android Studio with Android SDK Platform Tools.
- The **Android Auto Desktop Head Unit Emulator** installed from Android Studio: **SDK Manager** > **SDK Tools**.
- A current Android Auto installation on the test phone.
- Android system developer options and **USB debugging** enabled on the phone.
- Android Auto developer mode enabled, as described in the previous section.

For local Squeezelite builds, install the native build dependencies listed in the [Squeezelite README](../README.md). The Android project currently uses Android SDK Platform 35, Build Tools 35.0.0, and NDK r27.2.12479018.

### Build And Install Both Apps

Use branches that contain the Android Auto code. The fork APK build overlay lives on `build/android-auto-apk`; it also changes the application IDs and names so the test apps can be installed alongside upstream releases.

```powershell
# From the squeezelite checkout
git switch build/android-auto-apk
Set-Location android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk

# From the lms-material-app checkout
git switch build/android-auto-apk
.\gradlew.bat :lms-material:assembleDebug
adb install -r lms-material\build\outputs\apk\debug\lms-material-debug.apk
```

If an Actions-built fork APK is already installed, Android will reject the debug APK because it uses a different signing key. Uninstall only the two fork packages first; this does not remove the upstream apps:

```powershell
adb uninstall org.lyrion.squeezelite.chrober
adb uninstall com.craigd.lmsmaterial.app.chrober
```

Start both phone apps once, connect each to LMS, and select **Squeezelite (chrober)** in **Settings** > **Local player** > **Player app** before starting the DHU.

### Connect The DHU Through ADB Tunnelling

ADB tunnelling is the most convenient starting point on Windows because DHU USB accessory mode may need a WinUSB driver and can interfere with the normal ADB connection.

1. Connect and unlock the phone, then verify that ADB sees it:

   ```powershell
   adb devices
   ```

2. In Android Auto's **Developer settings**, choose **Start head unit server**. Also confirm that **Previously connected cars** has **Add new cars to Android Auto** enabled.

3. Forward the DHU port and launch the emulator:

   ```powershell
   adb forward tcp:5277 tcp:5277
   Set-Location "$env:ANDROID_SDK_ROOT\extras\google\auto"
   .\desktop-head-unit.exe
   ```

   When `ANDROID_SDK_ROOT` is not set, use the Android SDK location shown by Android Studio instead.

4. Accept any Android Auto prompt on the phone. The DHU should then show the Android Auto launcher and `Lyrion (chrober)`.

Useful DHU commands are entered in the terminal that launched it:

```text
focus audio off
focus audio on
keycode media_play_pause
keycode media_next
keycode media_previous
```

`focus audio off` simulates the head unit using another audio source; `focus audio on` restores Android Auto audio focus. These are particularly useful when checking focus-loss and recovery behaviour.

### USB Accessory Mode Alternative

DHU 2.x can also act as a USB accessory directly:

```powershell
Set-Location "$env:ANDROID_SDK_ROOT\extras\google\auto"
.\desktop-head-unit.exe --usb
```

This is closer to a wired Android Auto connection, but on Windows it may require a WinUSB driver for the phone and can make ADB unavailable. Prefer ADB tunnelling unless accessory mode is specifically needed.

## Troubleshooting

- **The fork app is absent from Android Auto:** update Android Auto, recheck developer mode and **Unknown sources**, then reconnect Android Auto.
- **Lyrion reports no player:** launch `Squeezelite (chrober)` on the phone first and verify that Lyrion's local player setting explicitly names the fork app.
- **DHU does not connect:** unlock the phone, ensure the head unit server is running, run `adb devices`, then repeat `adb forward tcp:5277 tcp:5277`.
- **Audio differs between DHU and a car:** record the phone model, Android and Android Auto versions, wired or wireless connection type, and head-unit model. DHU validates the standard projected Android Auto path but cannot model every OEM audio-policy implementation.

For the current Android Auto behaviour and test reports, see [Squeezelite issue #2](https://github.com/chrober/squeezelite/issues/2).
