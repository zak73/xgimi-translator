# XGIMI Translator

**English / French overlay for the Chinese menus of XGIMI RS30-series projectors (China firmware).**
No root, fully offline, fully reversible.

## Compatibility

| Model | Status |
|---|---|
| **RS30 Ultra** (`XGIMI TV DOHKO`, Android 14) | ✅ Tested, works |
| **RS30 Pro** (`XGIMI TV DOHKO`, product `athena3pro`, Android 14) | ✅ Tested, works |
| RS30 Pro Max / Ultra Max (China) | ❓ Not tested, should work |
| Horizon 30 series (global) | ➖ Not needed: runs Google TV with normal language settings |

I only own an **RS30 Ultra**. According to the launch coverage, the four China-market RS30 models share:
- the same chip (MediaTek MT9861);
- the same system (GMUI 6.0);
So the Settings menus should be the same, and the translator should work, but this hasn't been checked on the other models.

If a model has menus the Ultra doesn't, those texts may stay in Chinese. Everything else gets translated normally.

**Tried it on another model?** Please open an [issue](../../issues) to say whether it works, so this table can be updated.

---

## The problem

On the China-market firmware you **can't** change the system language:

- Setting the language through ADB (`settings put system system_locales en-US`) gets reverted at boot with a popup: *"Language Function Offline Notification – English is no longer supported in this release."*
- The Settings app (`com.android.newsettings`) was built with **Chinese strings only**. Even if you bypassed the check, there's nothing to display in another language.
- Per-app locales and MoreLocale don't help. Resource overlays (`cmd overlay fabricate`) need root.

## The solution

An **accessibility service** that translates the screen on the fly:

1. it takes a screenshot (Android 11+ accessibility API);
2. it reads the Chinese text with **ML Kit on-device OCR**, which is built into the app and doesn't need Google Play services;
3. it matches each line against a dictionary of ~2,100 strings from XGIMI Settings and the other XGIMI apps (launcher, file manager, player, system UI, installer), tolerating OCR errors (traditional characters, icon glyphs, misread characters);
4. it draws the **English or French** translation on top, in the original colors.

On Android 14 it captures only the menu window, so translations stay on screen while you navigate and are swapped in one go: about 0.2 s after a key press on screens it has already seen (remembered across restarts), 0.5–0.8 s on new screens, where only the part of the screen that changed is read again. Video players and HDMI input are never scanned. A watchdog restarts the service if the firmware kills it, at boot or after an update.

---

On my RS30 Ultra the Android developer options are hidden, so here’s what worked for me:

Download the SystemUI Tuner APK (by Zachary Wander, from GitHub or APKMirror) and put it on a FAT32 USB stick.
On the projector, open the File Manager, find the APK on the USB stick and install it (allow unknown sources if it asks).
Open SystemUI Tuner and go to Developer (开发者). Turn on ADB (启用 ADB) and Wireless ADB.
Find the projector’s IP address in Settings → Network, then on your PC:
adb connect <PROJECTOR_IP>:5555
adb devices
Accept the “Allow USB debugging?” prompt on the projector.

Another user (RS30 Ultra Max) said he used an app called “Z7X-ADB” instead, so that’s an alternative if this doesn’t work for you.

## Install

You need **ADB** on a computer ([Android platform-tools](https://developer.android.com/tools/releases/platform-tools)). On the projector, enable developer options, then **USB debugging** and **network debugging**.

1. Download **`xgimi-translator-v1.0.apk`** from the [Releases](../../releases) page.
2. Connect and install it:
   ```
   adb connect <PROJECTOR_IP>:5555
   adb install -t xgimi-translator-v1.0.apk
   ```
3. Enable the service, **keeping any services that are already enabled**:
   ```
   adb shell settings get secure enabled_accessibility_services
   ```
   - If the answer is `null` or empty:
     ```
     adb shell settings put secure enabled_accessibility_services fr.traducteur.xgimi/.TranslateService
     ```
   - Otherwise, add ours after a `:`. For example, with Projectivy Launcher:
     ```
     adb shell settings put secure enabled_accessibility_services "com.spocky.projengmenu/.services.ProjectivyAccessibilityService:fr.traducteur.xgimi/.TranslateService"
     ```
   Then:
   ```
   adb shell settings put secure accessibility_enabled 1
   ```
4. Allow automatic restart. Do this once; it's optional but recommended:
   ```
   adb shell pm grant fr.traducteur.xgimi android.permission.WRITE_SECURE_SETTINGS
   ```
5. On the projector, open the **Traducteur XGIMI** app and press **"Langue : Français"** to switch to **English**.

Open XGIMI Settings: the menus now show in your language.

> Android doesn't let an app enable itself as an accessibility service. The XGIMI firmware hides the accessibility screen, so step 3 has to go through ADB. You only do it **once**: the setting survives reboots and app updates.

## Uninstall

```
adb shell settings put secure enabled_accessibility_services "<your previous value>"
adb uninstall fr.traducteur.xgimi
```

---

## Build it yourself

1. Open this folder in **Android Studio**. If it asks for a Gradle JVM, pick **JVM 21**.
2. Use **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. The APK is in `app/build/outputs/apk/debug/`.

## Languages

The dictionaries are plain text files in `app/src/main/assets/`:

| File | Language |
|---|---|
| `dictionnaire_zh_en.tsv` | English |
| `dictionnaire_zh_fr.tsv` | French |

Format: `Chinese text<TAB>translation`, one per line. To add a language, copy one file, translate the right-hand column, and add the language code in `TranslateService.language()` and in the app's language button.

## Debugging

System logs (`logd`) are disabled on this firmware, so the app keeps its own log:

```
adb shell run-as fr.traducteur.xgimi cat files/journal.txt
```

## Bonus: debloat list

[`debloat-rs30-ultra.txt`](debloat-rs30-ultra.txt) lists the factory packages (ads, telemetry, app store…) that can be disabled safely with `pm disable-user`. Undo with `pm default-state --user 0`. It also lists the packages **not** to touch.

---

## Disclaimer

Independent project, not affiliated with XGIMI. Use it at your own risk. **Turn off network debugging when you're done**: ADB on port 5555 is open to your whole local network.

## License

MIT, see [`LICENSE`](LICENSE). The dictionaries' Chinese source strings come from the device's own firmware. The English and French translations are original.

*Built with the help of Claude (Anthropic's AI): ADB investigation, dictionary translations and app code.*
