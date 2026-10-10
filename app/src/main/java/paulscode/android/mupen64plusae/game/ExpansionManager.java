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
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Installed second-screen expansions. Imported .exp files are copied into the app's own storage
 * (files/expansions/&lt;id&gt;.exp), so the original file can be deleted afterwards.
 */
public final class ExpansionManager
{
    private static final String TAG = "ExpansionManager";
    private static final long MAX_SIZE = 32L * 1024 * 1024;

    private ExpansionManager() {}

    private static File dir(@NonNull Context context)
    {
        File d = new File(context.getFilesDir(), "expansions");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** All installed expansions, sorted by name. Broken files are skipped. */
    @NonNull
    public static List<Expansion> list(@NonNull Context context)
    {
        List<Expansion> out = new ArrayList<>();
        File[] files = dir(context).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".exp")) continue;
            try {
                out.add(Expansion.load(f));
            } catch (IOException e) {
                Log.w(TAG, "Skipping broken expansion " + f.getName() + ": " + e.getMessage());
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** The installed expansion for this ROM, or null. */
    @Nullable
    public static Expansion findFor(@NonNull Context context, String header, byte countryCode, String crc, String md5)
    {
        for (Expansion e : list(context)) {
            if (e.matches(header, countryCode, crc, md5)) return e;
        }
        return null;
    }

    /**
     * Import an .exp chosen by the user. It is checked first; an expansion with the same id is
     * replaced (so importing a newer version updates it). Returns the installed expansion.
     */
    @NonNull
    public static Expansion importFromUri(@NonNull Context context, @NonNull Uri uri) throws IOException
    {
        File tmp = new File(context.getCacheDir(), "import.exp.tmp");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("can't open file");
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_SIZE) throw new IOException("file too big (max 32 MB)");
                out.write(buf, 0, n);
            }
        }
        try {
            Expansion probe = Expansion.load(tmp); // throws if it isn't a valid expansion
            probe.close();
            File target = new File(dir(context), probe.id + ".exp");
            if (target.exists() && !target.delete()) throw new IOException("can't replace " + target.getName());
            if (!tmp.renameTo(target)) throw new IOException("can't store expansion");
            return Expansion.load(target);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Remove an installed expansion. */
    public static boolean delete(@NonNull Expansion expansion)
    {
        expansion.close();
        return expansion.file.delete();
    }
}
