/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 *
 * This file is part of Mupen64PlusAE.
 *
 * Mupen64PlusAE is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Mupen64PlusAE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Mupen64PlusAE. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package paulscode.android.mupen64plusae;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import paulscode.android.mupen64plusae.game.SecondScreen;

/**
 * "Update": checks the M64-DS releases on GitHub, downloads the newer APK and hands it to the
 * Android installer (which always asks the user to confirm).
 */
public final class Updater
{
    private static final String TAG = "Updater";
    public static final String REPO = "Hydriostatic/mupen64plus-ae";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";
    private static final Pattern NUMBER = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private Updater() {}

    /** What GitHub has. */
    public static final class Release
    {
        public final String version, apkUrl, notes;
        public final long apkSize;
        Release(String version, String apkUrl, long apkSize, String notes)
        {
            this.version = version; this.apkUrl = apkUrl; this.apkSize = apkSize; this.notes = notes;
        }
    }

    public interface CheckListener { void onChecked(@Nullable Release latest, @Nullable String error); }
    public interface DownloadListener
    {
        void onProgress(int percent);
        void onDone(@Nullable File apk, @Nullable String error);
    }

    private static Release sLatest;
    private static boolean sChecked;

    /** The last result of {@link #check}, or null. */
    @Nullable public static Release latest() { return sLatest; }
    public static boolean checked() { return sChecked; }

    /** This app's version, like "1.0.25". */
    @NonNull
    public static String installedVersion(Context context)
    {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            Matcher m = NUMBER.matcher(name != null ? name : "");
            return m.find() ? m.group() : (name != null ? name : "?");
        } catch (Exception e) {
            return "?";
        }
    }

    /** Is {@code a} newer than {@code b}? (x.y.z) */
    public static boolean newer(String a, String b)
    {
        Matcher ma = NUMBER.matcher(a), mb = NUMBER.matcher(b);
        if (!ma.find()) return false;
        if (!mb.find()) return true;
        for (int i = 1; i <= 3; i++) {
            int x = Integer.parseInt(ma.group(i)), y = Integer.parseInt(mb.group(i));
            if (x != y) return x > y;
        }
        return false;
    }

    public static boolean updateAvailable(Context context)
    {
        return sLatest != null && newer(sLatest.version, installedVersion(context));
    }

    private static HttpURLConnection open(String url) throws IOException
    {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "M64-DS-Updater");
        return c;
    }

    /** Ask GitHub for the latest release (in the background; the listener runs on the main thread). */
    public static void check(final CheckListener listener)
    {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Release r = null;
            String error = null;
            HttpURLConnection c = null;
            try {
                c = open(LATEST);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("GitHub answered " + code);
                String body;
                try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    body = out.toString("UTF-8");
                }
                JSONObject json = new JSONObject(body);
                Matcher m = NUMBER.matcher(json.optString("tag_name", "") + " " + json.optString("name", ""));
                if (!m.find()) throw new IOException("no version in the release");
                JSONArray assets = json.optJSONArray("assets");
                String url = null;
                long size = 0;
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                        url = a.optString("browser_download_url", null);
                        size = a.optLong("size", 0);
                        break;
                    }
                }
                if (url == null) throw new IOException("the release has no APK");
                r = new Release(m.group(), url, size, json.optString("body", ""));
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                if (c != null) c.disconnect();
            }
            final Release result = r;
            final String err = error;
            main.post(() -> {
                if (result != null) sLatest = result;
                sChecked = true;
                if (listener != null) listener.onChecked(result, err);
            });
        }, "UpdateCheck").start();
    }

    /** Download the release's APK into the app's cache. */
    public static void download(final Context context, final Release release, final DownloadListener listener)
    {
        final Handler main = new Handler(Looper.getMainLooper());
        final File dir = new File(context.getCacheDir(), "update");
        new Thread(() -> {
            File out = null;
            String error = null;
            HttpURLConnection c = null;
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                File tmp = new File(dir, "M64-DS-" + release.version + ".apk.part");
                c = open(release.apkUrl);
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("download answered " + code);
                long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : release.apkSize;
                try (InputStream in = c.getInputStream(); FileOutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    long got = 0;
                    int n, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            final int pct = (int) Math.min(100, got * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                main.post(() -> listener.onProgress(pct));
                            }
                        }
                    }
                }
                out = new File(dir, "M64-DS-" + release.version + ".apk");
                if (!tmp.renameTo(out)) throw new IOException("can't save the download");
            } catch (Exception e) {
                Log.w(TAG, "Update download failed", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
                out = null;
            } finally {
                if (c != null) c.disconnect();
            }
            final File apk = out;
            final String err = error;
            main.post(() -> listener.onDone(apk, err));
        }, "UpdateDownload").start();
    }

    /**
     * Open the Android installer for the APK. The first time, Android asks to allow installing
     * apps from M64-DS; this opens that setting instead and returns false.
     */
    public static boolean install(Activity activity, File apk)
    {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            Intent allow = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            SecondScreen.sSystemPickerOpen = true; // another app covers the second screen on purpose
            SecondScreen.startActivity(activity, allow);
            return false;
        }
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".filesprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        SecondScreen.sSystemPickerOpen = true;
        SecondScreen.startActivity(activity, intent);
        return true;
    }
}
