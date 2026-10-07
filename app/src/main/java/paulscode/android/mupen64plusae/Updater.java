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
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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

/**
 * Automatic updates: on start the app asks GitHub for the newest build of this version
 * (releases tagged "exp-x.y.z"), and when there's a newer one it offers to download it and hands
 * the APK to the Android installer (which always asks the user to confirm).
 */
public final class Updater
{
    private static final String TAG = "Updater";
    public static final String REPO = "Hydriostatic/mupen64plus-ae";
    /** Only releases of this app (the repository also has releases of other builds). */
    public static final String TAG_PREFIX = "exp-";
    private static final String RELEASES = "https://api.github.com/repos/" + REPO + "/releases?per_page=100";
    private static final Pattern NUMBER = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private Updater() {}

    /** A build on GitHub. */
    public static final class Release
    {
        public final String version, apkUrl, notes;
        public final long apkSize;
        Release(String version, String apkUrl, long apkSize, String notes)
        {
            this.version = version; this.apkUrl = apkUrl; this.apkSize = apkSize; this.notes = notes;
        }
    }

    interface CheckListener { void onChecked(@Nullable Release latest, @Nullable String error); }
    interface DownloadListener
    {
        void onProgress(int percent);
        void onDone(@Nullable File apk, @Nullable String error);
    }

    /** The automatic check runs once per app start. */
    private static boolean sAutoChecked = false;
    /** Downloaded, waiting for the user to allow installing apps from this one. */
    private static File sPendingApk;

    // ---------------------------------------------------------------------------------------------
    // What the main screen calls
    // ---------------------------------------------------------------------------------------------

    /** On start: look for a newer build quietly and offer it if there is one. */
    public static void autoCheck(@NonNull Activity activity)
    {
        if (sAutoChecked) return;
        sAutoChecked = true;
        check((latest, error) -> {
            if (latest != null && newer(latest.version, installedVersion(activity)) && alive(activity)) {
                offer(activity, latest);
            }
        });
    }

    /** Menu "Check for updates": same, but always says what it found. */
    public static void checkNow(@NonNull Activity activity)
    {
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
        check((latest, error) -> {
            if (!alive(activity)) return;
            if (latest == null) {
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.update_title)
                        .setMessage(activity.getString(R.string.update_failed, error))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            } else if (newer(latest.version, installedVersion(activity))) {
                offer(activity, latest);
            } else {
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.update_title)
                        .setMessage(activity.getString(R.string.update_upToDate, installedVersion(activity)))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    /** Back from the "install unknown apps" setting: continue with the download we already have. */
    public static void onResume(@NonNull Activity activity)
    {
        if (sPendingApk != null && sPendingApk.exists() &&
                activity.getPackageManager().canRequestPackageInstalls()) {
            File apk = sPendingApk;
            sPendingApk = null;
            install(activity, apk);
        }
    }

    private static boolean alive(Activity a)
    {
        return !a.isFinishing() && !a.isDestroyed();
    }

    private static void offer(@NonNull Activity activity, @NonNull Release r)
    {
        String message = activity.getString(R.string.update_available, r.version, installedVersion(activity));
        if (!TextUtils.isEmpty(r.notes)) message += "\n\n" + r.notes.trim();
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_title)
                .setMessage(message)
                .setPositiveButton(R.string.update_install, (d, w) -> downloadAndInstall(activity, r))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private static void downloadAndInstall(@NonNull Activity activity, @NonNull Release r)
    {
        final AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_title)
                .setMessage(activity.getString(R.string.update_downloading, 0))
                .setCancelable(false)
                .show();
        download(activity, r, new DownloadListener() {
            @Override public void onProgress(int percent)
            {
                progress.setMessage(activity.getString(R.string.update_downloading, percent));
            }

            @Override public void onDone(@Nullable File apk, @Nullable String error)
            {
                if (progress.isShowing() && alive(activity)) progress.dismiss();
                if (!alive(activity)) return;
                if (apk == null) {
                    Toast.makeText(activity, activity.getString(R.string.update_failed, error), Toast.LENGTH_LONG).show();
                } else {
                    install(activity, apk);
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // GitHub
    // ---------------------------------------------------------------------------------------------

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
    static boolean newer(String a, String b)
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

    private static HttpURLConnection open(String url) throws IOException
    {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "M64-EXP-Updater");
        return c;
    }

    /** The newest release of this app on GitHub (in the background; the listener runs on the main thread). */
    static void check(final CheckListener listener)
    {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Release best = null;
            String error = null;
            HttpURLConnection c = null;
            try {
                c = open(RELEASES);
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
                JSONArray releases = new JSONArray(body);
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject json = releases.getJSONObject(i);
                    String tag = json.optString("tag_name", "");
                    if (!tag.startsWith(TAG_PREFIX) || json.optBoolean("draft") || json.optBoolean("prerelease")) continue;
                    Matcher m = NUMBER.matcher(tag);
                    if (!m.find()) continue;
                    JSONArray assets = json.optJSONArray("assets");
                    String url = null;
                    long size = 0;
                    for (int j = 0; assets != null && j < assets.length(); j++) {
                        JSONObject a = assets.getJSONObject(j);
                        if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                            url = a.optString("browser_download_url", null);
                            size = a.optLong("size", 0);
                            break;
                        }
                    }
                    if (url == null) continue;
                    if (best == null || newer(m.group(), best.version)) {
                        best = new Release(m.group(), url, size, json.optString("body", ""));
                    }
                }
                if (best == null) error = "no builds published yet";
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                if (c != null) c.disconnect();
            }
            final Release result = best;
            final String err = error;
            main.post(() -> listener.onChecked(result, err));
        }, "UpdateCheck").start();
    }

    /** Download the release's APK into the app's cache. */
    static void download(final Context context, final Release release, final DownloadListener listener)
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
                File tmp = new File(dir, "M64-EXP-" + release.version + ".apk.part");
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
                out = new File(dir, "M64-EXP-" + release.version + ".apk");
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
     * Open the Android installer for the APK. The first time, Android has to allow installing
     * apps from this one: that setting opens instead, and the install continues on return.
     */
    static void install(Activity activity, File apk)
    {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            sPendingApk = apk;
            Toast.makeText(activity, R.string.update_allowInstall, Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".filesprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(intent);
    }
}
