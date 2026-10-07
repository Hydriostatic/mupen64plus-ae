#!/bin/bash
# Smoke test: install the APK, add a simulated second screen (like the AYN Thor's), open the
# app and its menus/settings, and save screenshots of every display + the log after each step.
set +e
PKG=org.mupen64plusae.v3.alpha.thor
OUT=smoke
mkdir -p "$OUT"
n=0

shots() {  # $1 = step name
  n=$((n + 1))
  local name; name=$(printf "%02d_%s" "$n" "$1")
  for id in $(adb shell dumpsys SurfaceFlinger --display-id 2>/dev/null | sed -n 's/^Display \([0-9]*\).*/\1/p'); do
    adb exec-out screencap -p -d "$id" > "$OUT/${name}_display${id}.png" 2>/dev/null
  done
  adb exec-out screencap -p > "$OUT/${name}.png" 2>/dev/null
  adb shell dumpsys activity activities | grep -E "topResumedActivity|ResumedActivity|mFocusedApp" > "$OUT/${name}_activities.txt"
  adb logcat -d > "$OUT/${name}_logcat.txt"
  adb logcat -d -b events | grep -E "am_(create|finish|destroy|resume|on_resume|pause|stop|focus|set_resumed)" | grep -i mupen > "$OUT/${name}_events.txt"
}

adb root; sleep 3
adb wait-for-device
adb install -r -g apk/*.apk > "$OUT/install.txt" 2>&1
cat "$OUT/install.txt"

# Main screen like the Thor's top screen (landscape 1920x1080), second screen 1080x1240
adb shell wm size 1080x1920
adb shell wm density 420
# Second screen 1080x1240 (the Thor's bottom screen)
adb shell settings put global overlay_display_devices 1080x1240/320
sleep 6
adb shell dumpsys display | grep -E "mDisplayId=|DisplayDeviceInfo|mBaseDisplayInfo" > "$OUT/displays.txt"
SECOND=$(adb shell dumpsys display | grep -o 'mDisplayId= *[0-9]*' | grep -o '[0-9]*$' | sort -u | grep -v '^0$' | head -1)
echo "second display: $SECOND" | tee -a "$OUT/displays.txt"

adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null
adb logcat -c

# 1) Start the app the normal way
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 30
shots app_start

# 2) Bottom screen: tap the first button (Settings), then the first row of its popup
tapb() { adb shell input -d "$SECOND" tap "$1" "$2"; sleep "${3:-4}"; }
if [ -n "$SECOND" ]; then
  tapb 191 272 5; shots bc_settings_button
  for y in 260 380 500; do
    tapb 540 $y 8; shots "bc_popup_tap_$y"
    tapb 540 420 5; shots "bc_page_${y}_option1"
    tapb 540 700 5; shots "bc_page_${y}_option2"
    adb shell input -d "$SECOND" keyevent KEYCODE_BACK; sleep 3
    adb shell input -d "$SECOND" keyevent KEYCODE_BACK; sleep 3
    tapb 191 272 4
  done
  adb shell input -d "$SECOND" keyevent KEYCODE_BACK; sleep 3
  tapb 540 272 5; shots bc_profiles_button
  tapb 540 380 8; shots bc_profiles_row
  adb shell input -d "$SECOND" keyevent KEYCODE_BACK; sleep 3
  tapb 889 272 5; shots bc_tools_button
  tapb 540 380 8; shots bc_tools_row
fi

# 3) Settings pages opened directly on the main screen, then tap options in them
for A in DisplayPrefsActivity AudioPrefsActivity InputPrefsActivity LibraryPrefsActivity DataPrefsActivity; do
  adb shell am start -n $PKG/paulscode.android.mupen64plusae.persistent.$A
  sleep 6; shots "${A}_open"
  adb shell input tap 540 1300; sleep 4; shots "${A}_tap1"
  adb shell input keyevent KEYCODE_BACK; sleep 2
  adb shell input tap 540 1600; sleep 4; shots "${A}_tap2"
  adb shell input keyevent KEYCODE_BACK; sleep 2
  adb shell am force-stop $PKG; sleep 2
done

adb logcat -d -b crash > "$OUT/crash_buffer.txt"
adb logcat -d | grep -E "AndroidRuntime|FATAL|Exception|M64|SecondScreen|DualScreen|Updater" > "$OUT/errors.txt"
exit 0
