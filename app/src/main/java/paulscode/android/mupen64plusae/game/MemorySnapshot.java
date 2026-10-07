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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Saves the whole N64 memory (8 MB) to Download/Mupen64BT, for finding where a game keeps
 * things (e.g. the player's position) when writing an expansion.
 */
final class MemorySnapshot
{
    private static final String TAG = "MemorySnapshot";
    private static boolean sSaving = false;

    private MemorySnapshot() {}

    /** Save in the background; shows a toast (on {@code toastContext}'s screen) when done. */
    static void save(final Context toastContext, final String prefix)
    {
        if (sSaving) return;
        sSaving = true;
        final Context app = toastContext.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final String name = prefix + "_memory_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".bin";
        Toast.makeText(toastContext, "Saving memory snapshot…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String result;
            try {
                byte[] all = new byte[BanjoTooieStats.RDRAM_SIZE];
                final int chunk = 0x10000;
                byte[] buf = new byte[chunk];
                for (int off = 0; off < all.length; off += chunk) {
                    if (!BanjoTooieStats.readRaw(off, buf, chunk)) throw new IllegalStateException("game not running");
                    System.arraycopy(buf, 0, all, off, chunk);
                }
                result = write(app, name, all);
            } catch (Exception ex) {
                Log.e(TAG, "Snapshot failed", ex);
                result = null;
            }
            final String msg = result != null ? "Saved " + result : "Couldn't save the memory snapshot";
            main.post(() -> {
                sSaving = false;
                Toast.makeText(toastContext, msg, Toast.LENGTH_LONG).show();
            });
        }, "MemorySnapshot").start();
    }

    private static String write(Context ctx, String name, byte[] data) throws Exception
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = ctx.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Mupen64BT");
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("no download uri");
            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("no output stream");
                os.write(data);
            }
            return "Download/Mupen64BT/" + name;
        }
        File dir = new File(ctx.getExternalFilesDir(null), "Mupen64BT");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, name);
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(data);
        }
        return f.getAbsolutePath();
    }
}
