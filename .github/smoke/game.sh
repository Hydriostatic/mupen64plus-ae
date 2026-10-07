#!/bin/bash
# Game smoke test: runs a public-domain sound test ROM with a simulated second screen and
# records the audio state (is the game's sound playing?) before/after using the bottom screen.
set +e
PKG=org.mupen64plusae.v3.alpha.thor
OUT=smoke
mkdir -p "$OUT"
n=0
shots() {
  n=$((n + 1)); local name; name=$(printf "%02d_%s" "$n" "$1")
  adb exec-out screencap -p > "$OUT/${name}.png" 2>/dev/null
  adb shell dumpsys activity activities | grep -E "topResumedActivity|ResumedActivity" > "$OUT/${name}_activities.txt"
  adb shell dumpsys audio | sed -n '/players:/,/ducked players/p' > "$OUT/${name}_audio_players.txt"
  adb shell dumpsys media.audio_flinger | grep -iE "^  *[0-9]+ +[a-z]|Output thread|Standby:|Tracks of thread|active tracks|Active  *Client|stopped|paused|muted" > "$OUT/${name}_flinger.txt"
  adb logcat -d > "$OUT/${name}_logcat.txt"
}
adb root; sleep 3; adb wait-for-device
adb install -r -g apk/*.apk > "$OUT/install.txt" 2>&1
adb shell wm size 1080x1920; adb shell wm density 420
adb shell settings put global overlay_display_devices 1080x1240/320
sleep 6
SECOND=$(adb shell dumpsys display | grep -o 'mDisplayId= *[0-9]*' | grep -o '[0-9]*$' | sort -u | grep -v '^0$' | head -1)
echo "second=$SECOND" > "$OUT/displays.txt"
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell media volume --stream 3 --set 10 2>/dev/null
adb push rom/test.n64 /sdcard/Download/test.n64
# First run sets the app up, then launch the ROM like a file manager would
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1; sleep 30; shots first_start
adb logcat -c
adb shell am start -a android.intent.action.VIEW -d file:///sdcard/Download/test.n64 -t application/octet-stream -n $PKG/paulscode.android.mupen64plusae.SplashActivity
sleep 35; shots game_running
adb shell input -d "$SECOND" tap 540 900; sleep 6; shots after_bottom_tap
adb shell input -d "$SECOND" tap 540 200; sleep 6; shots after_bottom_tap2
adb shell input tap 540 960; sleep 6; shots after_top_tap
adb shell input keyevent KEYCODE_BACK; sleep 6; shots back_key
exit 0
