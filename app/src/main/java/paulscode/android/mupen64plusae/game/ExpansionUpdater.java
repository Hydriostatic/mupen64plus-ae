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
package paulscode.android.mupen64plusae.game;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import paulscode.android.mupen64plusae.Updater;

/**
 * The "update" button next to each expansion: looks for a newer copy of that .exp on GitHub and,
 * if there is one, replaces the installed file.
 *
 * Where it looks (manifest "update"):
 *  - nothing: the releases of the expansions repository (EXPANSIONS_REPO), then this app's (Updater.REPO);
 *  - "github:owner/repo" (or "owner/repo", or a github.com/owner/repo link): that repository's releases;
 *  - any other https link: that file, downloaded directly.
 * In releases, the newest release with an asset named &lt;id&gt;.exp is used (also accepted: a name
 * the id starts with, like kirby64.exp for "kirby64-usa", or &lt;id&gt;-anything.exp).
 */
public final class ExpansionUpdater
{
    private static final String TAG = "ExpansionUpdater";
    private static final long MAX_SIZE = 32L * 1024 * 1024;
    /** Where expansions are published, looked at first when the manifest doesn't say. */
    public static final String EXPANSIONS_REPO = "Hydriostatic/m64ds-expansions";
    private static final Pattern GITHUB_REPO =
            Pattern.compile("^(?:github:|https?://github\\.com/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");

    private ExpansionUpdater() {}

    public enum Outcome { UPDATED, UP_TO_DATE, REMOTE_OLDER, NOT_FOUND, FAILED }

    public static final class Result
    {
        public final Outcome outcome;
        /** The version found online (UPDATED / UP_TO_DATE / REMOTE_OLDER), or the error (FAILED). */
        public final String detail;
        Result(Outcome outcome, String detail) { this.outcome = outcome; this.detail = detail; }
    }

    public interface Listener { void onDone(@NonNull Result result); }

    /** Check and update one expansion in the background; the listener runs on the main thread. */
    public static void update(@NonNull Context context, @NonNull String id, @NonNull String version,
                              @NonNull String source, @NonNull File installed, @NonNull Listener listener)
    {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Result r;
            File tmp = new File(app.getCacheDir(), "update-" + id + ".exp.part");
            try {
                String url = findUrl(id, source);
                if (url == null) {
                    r = new Result(Outcome.NOT_FOUND, null);
                } else {
                    download(url, tmp);
                    Expansion probe = Expansion.load(tmp); // throws if it isn't a valid expansion
                    String remoteId = probe.id, remoteVersion = probe.version;
                    probe.close();
                    if (!remoteId.equals(id)) {
                        throw new IOException("the file online is a different expansion (" + remoteId + ")");
                    }
                    int cmp = compareVersions(remoteVersion, version);
                    if (cmp < 0) {
                        r = new Result(Outcome.REMOTE_OLDER, remoteVersion);
                    } else if (cmp == 0 && sameContent(tmp, installed)) {
                        r = new Result(Outcome.UP_TO_DATE, remoteVersion);
                    } else {
                        // Newer, or same version number but changed: install it
                        ExpansionManager.install(app, tmp).close();
                        r = new Result(Outcome.UPDATED, remoteVersion);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Expansion update failed for " + id, e);
                r = new Result(Outcome.FAILED, e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            final Result result = r;
            main.post(() -> listener.onDone(result));
        }, "ExpansionUpdate").start();
    }

    /** The download link of the newest copy, or null when there is none. */
    @Nullable
    private static String findUrl(@NonNull String id, @NonNull String source) throws Exception
    {
        if (source.isEmpty()) {
            // Default: the expansions repository first, then this app's own
            String url = findInReleases(id, EXPANSIONS_REPO);
            return url != null ? url : findInReleases(id, Updater.REPO);
        }
        Matcher m = GITHUB_REPO.matcher(source);
        if (m.matches()) return findInReleases(id, m.group(1) + "/" + m.group(2));
        if (source.startsWith("https://") || source.startsWith("http://")) return source; // a direct link
        throw new IOException("unknown update source: " + source);
    }

    @Nullable
    private static String findInReleases(@NonNull String id, @NonNull String repo) throws Exception
    {
        HttpURLConnection c = open("https://api.github.com/repos/" + repo + "/releases?per_page=100");
        String body;
        try {
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code == 404) throw new IOException("repository " + repo + " not found (or private)");
            if (code != 200) throw new IOException("GitHub answered " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                body = out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }

        // Releases come newest first; take the first one that has this expansion, preferring
        // an exact <id>.exp over a looser name within the same release.
        JSONArray releases = new JSONArray(body);
        for (int i = 0; i < releases.length(); i++) {
            JSONObject rel = releases.getJSONObject(i);
            if (rel.optBoolean("draft")) continue;
            JSONArray assets = rel.optJSONArray("assets");
            String loose = null;
            for (int j = 0; assets != null && j < assets.length(); j++) {
                JSONObject a = assets.getJSONObject(j);
                String name = a.optString("name", "");
                String url = a.optString("browser_download_url", null);
                if (url == null) continue;
                int how = nameMatches(name, id);
                if (how == 2) return url;
                if (how == 1 && loose == null) loose = url;
            }
            if (loose != null) return loose;
        }
        return null;
    }

    /** 2 = exactly &lt;id&gt;.exp, 1 = a looser match, 0 = no. */
    static int nameMatches(String assetName, String id)
    {
        String a = assetName.toLowerCase(Locale.US);
        if (!a.endsWith(".exp")) return 0;
        String base = a.substring(0, a.length() - 4);
        String i = id.toLowerCase(Locale.US);
        if (base.equals(i)) return 2;
        if (i.startsWith(base + "-") || i.startsWith(base + "_")) return 1;     // kirby64.exp for kirby64-usa
        if (base.startsWith(i + "-") || base.startsWith(i + "_")) return 1;     // kirby64-usa-1.2.exp
        return 0;
    }

    private static HttpURLConnection open(String url) throws IOException
    {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "M64-EXP-Expansions");
        return c;
    }

    private static void download(String url, File to) throws IOException
    {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("download answered " + code);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_SIZE) throw new IOException("file too big (max 32 MB)");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Compare "1.2", "1.10", "2.0.1"… by their numbers (missing parts count as 0). */
    static int compareVersions(String a, String b)
    {
        List<Integer> x = numbers(a), y = numbers(b);
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int p = i < x.size() ? x.get(i) : 0, q = i < y.size() ? y.get(i) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static List<Integer> numbers(String v)
    {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+").matcher(v != null ? v : "");
        while (m.find()) {
            try { out.add(Integer.parseInt(m.group())); } catch (NumberFormatException e) { out.add(Integer.MAX_VALUE); }
        }
        return out;
    }

    private static boolean sameContent(File a, File b)
    {
        if (!b.exists() || a.length() != b.length()) return false;
        try {
            return MessageDigest.isEqual(md5(a), md5(b));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] md5(File f) throws Exception
    {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return md.digest();
    }
}
